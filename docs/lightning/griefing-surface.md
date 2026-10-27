# The griefing surface: four liveness holes in the co-signing path

The security review found four ways one member, or the operator acting alone, can
stall the group Lightning wallet without ever touching its money. None of them
steals anything; all of them contradict the trust table in
`docs/group-lightning-wallet.md` (lines 325-327), which gives a single member no
way to stop the wallet and the operator only "choose when to send" and the
`error`-message griefing. This plan closes the four holes. It changes no wire
kind and nothing in the forks' signing protocols; one phase adds a schema
migration, and says so.

Sibling plans cover what this one does not: `force-close-revoked-commitment.md`,
`first-commitment-policy.md`, `mutual-close-amount.md`, `restored-device-marks.md`,
`node-seed-in-flight-exposure.md`, `signer-hygiene.md`.

## The finding

### (a) One member can burn pool labels faster than provisioning refills them

`fileEcho` files any participant's echo with no precondition beyond room
membership (`GroupLightningCoSigner.kt:217-223`; the only check, at :220, is
`indexOf(wallet, event.pubKey) == null`). `maybeSign` then refuses the label
forever once two different hashes exist for it (:231-234), and permanence is
real: echo rows and the label ledger are INSERT-IGNORE and never pruned
(`IcebergCoSigningDao.kt:39-41,49-50`; `IcebergCoSigning.kt:35-56`). Pool labels
are publicly derivable — `poolLabel(icebergKey, counter)` is a tagged hash over
the statement's key and a counter (:81-82) — so the attack needs no information
beyond what every member has:

1. The malicious member computes `poolLabel` for counters `C…C+1000`, well past
   the assigned ones.
2. For each, it sends one ECHO (30359) naming that label and a garbage hash.
3. Every device files it. When the operator later assigns counter `C+k` to a real
   request, every device already holds a foreign hash for the label; the moment
   it echoes the real hash, `maybeSign` sees two hashes and refuses (:231-234).
4. Provisioning refills `POOL_WINDOW` = 64 counters ahead (:62) in batches of at
   most 128; one member with a loop burns them faster than the room refills them.
   The wallet halts. No signature was ever at risk, but the trust table says only
   `t-1` colluders can stop the wallet.

The design doc also promises that two hashes under one label "raises the alarm in
the transcript, because that is a coordinator equivocating"
(`docs/group-lightning-wallet.md:808-810`). What exists is one log line
(`GroupLightningCoSigner.kt:232`) that no person ever sees.

### (b) Budget and channel ledger are spent at echo time, before any signature exists

`handleRequest` runs `commitLedger` at :208, after the checks but before the echo
quorum and before any share is produced. `commitLedger` (:420-443) re-evaluates
the policy and, on a settling commitment, calls
`GroupLightningAuthorisations.consume` (:435), then upserts the channel ledger
(:441). The attack, operator-only, no collusion:

1. The group authorises a payment. The operator's node offers the HTLC, then
   requests the commitment that settles it. Every co-signer checks, echoes, and
   commits: budget consumed, channel ledger advanced to index `N`, txid `T1`.
2. The operator never completes the round — it withholds the aggregate and lets
   the request time out. No group signature exists; the channel is really still
   at `N-1`.
3. The budget is gone. The group must sign a fresh authorisation to retry — a
   `t`-of-`n` human round, per payment the operator chooses to burn.
4. Worse: the operator now proposes a *different* commitment at `N` — a new
   feerate, or the HTLC failed back. `CoSignerPolicy.checkRemoteCommitment`
   refuses it: "commitment N was already signed as a different transaction"
   (`CoSignerPolicy.kt:79-83`), even though nothing was signed. The channel can
   never advance past `N-1` on these devices again; the group's only way out is a
   force close. The review confirmed no theft follows — the floor arithmetic
   keeps every refusal in the safe direction — but the channel is wedged.

### (c) Any room member can kill a key ceremony with one event

`IcebergRitualManager.record` applies the participant check only to the three
round kinds (`IcebergRitualManager.kt:268-278`). The `CEREMONY_FAILURE` branch
(:279-282) calls `fail` for any sender. The session id rides on every ceremony
message in the room (the `SESSION_TAG` in `broadcast`, :532), so every room
member — not only the ceremony's participants — knows it. One forged
CEREMONY_FAILURE (30354) from a non-participant ends the ceremony on every
device, including on replay (`replayStoredMessages` re-records stored events,
:253-261). A participant can already abort a ceremony honestly (reveal garbage),
so this only widens the abort set to the whole room — griefing, no more.

### (d) One bad share wedges a request, and no one is named

`fileShare` stores any participant's share unchecked (:166-171); the row drops
the message's label field (`IcebergCoSigning.kt:59-65` has no label column,
though `CoSigningWire.SignatureShareMessage` carries one, `CoSigningWire.kt:124`).
The transport's `roundTwo` waits for `minOf(n, 2t-1)` shares and returns *all*
filed shares (`GroupIcebergTransport.kt:181-186`), and
`RemoteIcebergFundingSigner.sign` aggregates that whole set, with only the final
session verify as a backstop (:202-208). So:

1. A malicious member answers a real request with one garbage share. It is filed
   under their name, first-write-wins.
2. Aggregation either fails the degree check (> `t` shares) or — the scheme's
   known caveat — *passes* it at exactly `t` shares, because any `t` points fit
   some degree `t-1` polynomial (`iceberg.md`'s failure table; the review quotes
   the C tests). The final session verify then fails.
3. Either way the whole attempt fails with no attribution and no subset retry:
   the label is burned, the channel retries on lightning-kmp's schedule, and the
   member can repeat it on the next request. `Iceberg.verifyPartialSignature`
   exists (`Iceberg.kt:268`) and is never called anywhere in the app.

## Root cause

| vector | root cause |
|---|---|
| (a) | `fileEcho` files what this device cannot connect to anything it checked. The immutability that makes the label ledger safe makes a poisoned echo permanent, and labels are derivable by anyone. The transcript alarm the doc promises was never written. |
| (b) | `commitLedger` conflates two different commitments (:419's "at echo time: it is committed to it from here"). The label→hash commitment must be immediate and irrevocable — it guards a share. The *payment accounting* (budget, floor, index) is only real once this device actually signed, and `commitLedger` writes both at echo time. |
| (c) | `record`'s participant check enumerates the three round kinds and forgets CEREMONY_FAILURE. |
| (d) | Shares are aggregated sight-unseen because the final session verify (:205) was treated as the share check. It is a yes/no on the aggregate: it names no culprit, admits no retry, and at exactly `t` shares the degree check behind it is vacuous. Per-share verification before aggregation is the only real backstop. |

## The fix, in phases

### Phase 1 — a ceremony hears failure only from its participants

**Half a day. Closes (c). No schema, wire or fork change.**

- `IcebergRitualManager.record`, the CEREMONY_FAILURE branch
  (`IcebergRitualManager.kt:279-282`): apply the same check the round kinds get —
  `if (indexOf(session, sender) == null) { logger.w(…); return true }`. The
  existing `indexOf` (:116) already does the lookup. A genuine participant's
  failure still ends the ceremony, which is correct: any participant can abort
  anyway.

**Tests** (in `IcebergCeremonyJvmTest.kt`, named to fit):

- `a ceremony failure from outside the ceremony is ignored` — a fifth room
  member, not among the ceremony's participants, sends CEREMONY_FAILURE for the
  session id; the four devices complete the ceremony regardless.
- `a stored failure from outside the ceremony is ignored on replay` — the bogus
  event is stored before one device processes the proposal, so
  `replayStoredMessages` meets it; the ceremony still completes.

### Phase 2 — an echo counts only against a request this device can see, and equivocation is a transcript line

**Two days. Closes (a). No schema, wire or fork change; one new `ChatMessage`
type (an app-side string, not a column).**

The echo already carries `requestId` (`CoSigningWire.kt:116`), and requests are
room-stored inner events this device can re-read (`requestFor`,
`GroupLightningCoSigner.kt:263-266`). The gate is therefore request-existence,
not own-echo:

| | decision | why |
|---|---|---|
| gate | `fileEcho` files an echo only if this device holds a SIGNING_REQUEST with the echo's `requestId`, whose label equals the echo's, whose sender is the current operator in the echo's... the request's term, and whose counter recomputes to the label (`poolLabel`, :285) | a member cannot fabricate that: the MLS sender is the identity checked. Pre-burning fresh labels dies because no request exists for them yet. Full `check` is not re-run per echo — operator, term, label shape is enough, since `maybeSign` still requires this device's own ledger row (:228) before it signs anything |
| own-echo gating | **rejected**: file echoes only after this device echoed | an echo that beats its request to the device is dropped forever, and quorums silently undercount on relay reordering. Stashing and applying on own-echo re-enables pre-burn for the same-hash... and discarding stashed different-hash echoes kills the equivocation alarm that is the two-hash rule's whole point |
| refusal key | stays the label, not (label, request) | one label maps to one requestId through the gate; keying on the pair adds nothing |
| late echoes | a bounded in-memory stash of echoes that fail the gate, re-examined when a qualifying request is filed, dropped when the request's term ends | covers request/echo reordering without giving fabricated echoes a path in — a stashed echo only activates against a real operator request |

The Byzantine overlap argument (D7, `docs/group-lightning-wallet.md:318`; the
appendix arithmetic at :1087-1090) is untouched: who may echo, when, and how many
must agree are unchanged. The change is only which *foreign* echoes are filed,
and only in the case where no valid request exists — a case in which no honest
member ever echoes.

What remains after the fix: one member can still burn the label of one genuinely
outstanding request at a time (a wrong-hash echo against a real request), which
bounds the burn rate to the operator's request rate and makes every burn
attributed — the echo rows name the echoer. Recovery is the path that already
exists: the label times out, the operator retries the same transaction under a
fresh label (`RemoteIcebergFundingSigner.kt:105-106,119-120`; the channel retries
on its own schedule).

The alarm becomes a transcript line, as the doc promises. `GroupLightningCoSigner`
gains an `announce` mirroring `IcebergRitualManager.announce`
(`IcebergRitualManager.kt:492-506`): a `ChatMessage` with a new type
(`TYPE_GROUP_LIGHTNING_CO_SIGNER_ALARM`, beside the existing
`TYPE_GROUP_LIGHTNING_*` strings in `ChatMessage.kt:499-502`) and
`dkgSessionId = wallet.ceremony.id`, written when `maybeSign` sees two hashes
(:231). The wording names the participants whose echoes carry the differing hash
— the rows name them — and says "the operator showed two transactions under one
label, or a member is echoing hashes it was never asked to; this device signs
neither". It must not flatly accuse the operator: after this phase, a false-echo
attack and a true equivocation produce the same row pattern.

Also in this phase: update the trust table and Phase 9's alarm sentence in
`docs/group-lightning-wallet.md` (:325-327, :808-810) to match the code.

**Tests** (in `GroupLightningCoSigningJvmTest.kt`):

- `an echo that names no request of the operator's burns nothing` — one member
  pre-echoes garbage hashes for the next ten pool labels; the wallet then signs
  ten commitments without a hitch.
- `a member who echoes a hash it was not asked to sign is named, and the retry
  under a fresh label completes` — a malicious member false-echoes against a real
  request; the request times out, the transcript line names the member on every
  device, and the operator's retry under the next label is signed.
- `two hashes under one label from the operator end the label and say so in the
  transcript` — a hand-built second request from the operator under a used label;
  no device signs, and every device that saw both requests writes the alarm.

### Phase 3 — every share is verified on receipt, and a bad one names its sender

**Two days. Closes (d). No schema, wire or fork-protocol change; one fork *test*
in bitcoin-kmp's `commonTest`.**

`process` routes SIGNATURE_SHARE to `fileShare` on *every* device
(`GroupLightningCoSigner.kt:148`), not only the operator's, and every device
holds the published public shares in its ceremony record. So attribution needs no
new message and no trust in the operator:

- `fileShare` (:166-171): after the participant check, look the request up by
  `requestId`; if it is in hand, recompute the message (`messageOf`, :269-273)
  and the key-aggregation cache (as `maybeSign` does, :241-248), and run
  `Iceberg.verifyPartialSignature(share, publicShares[sender's index],
  request.contributions, n, t, groupKey, cache, message,
  request.cosignerAggregatedNonce)` (`Iceberg.kt:268`). A share that fails is not
  filed, and the device writes the Phase 2 alarm line naming the sender. If the
  request has not arrived yet, file the share unverified (the operator-side check
  below still catches it) — no alarm, because the request might legitimately be
  in flight.
- `GroupIcebergTransport.roundTwo` (:181-186): verify each filed share the same
  way as it appears (the transport has `wallet` and the request), exclude the bad
  ones from the returned list, and return as soon as `minOf(n, 2t-1)` *verified*
  shares are in — or, once `minOf(n, 2t-1)` shares are filed, as soon as `t`
  verified ones are. Subset retry then costs nothing: a bad share is simply not
  in the set, and the loop keeps waiting for the rest within the existing
  deadline.
- Check the share message's label against the request's before filing
  (:170 drops it today) — a share for another label is garbage by definition.

Verify-before-aggregate, never after: at exactly `t` shares the degree check
accepts a tampered share (the scheme's documented caveat), so the aggregate-level
session verify (:205) stays where it is as the final backstop, but no share
reaches it unverified. `RemoteIcebergFundingSigner` itself is unchanged.

**Tests:**

- `a garbage share is named, excluded, and the signature completes`
  (`GroupLightningCoSigningJvmTest.kt`) — the fixture's dispatch tampers one
  member's SIGNATURE_SHARE payload; the signature completes from the remaining
  members, and every device that had the request writes the alarm naming the
  member.
- `a tampered share fails verifyPartialSignature` — in the fork's bitcoin-kmp
  `commonTest` beside `IcebergTestsCommon.kt`, if the primitive's negative is not
  already pinned there.

### Phase 4 — the channel ledger keeps a signed prefix and one pending slot

**Four days. Closes (b). Schema 33: an `AutoMigration` adding nullable
`pendingCommitIndex`, `pendingCommitTxId`, `pendingRequestId`, `pendingLedger`,
`pendingConsumed` columns to `IcebergChannelLedger` (`IcebergCoSigning.kt:68-76`).
No wire or fork change.**

The rule is one sentence: **a device's irrevocable records move only when that
device has produced a share.** `IcebergChannelLedger`'s existing columns become
the *signed* record — the last counterparty commitment this device signed for the
channel — and the pending columns hold the one unevaluated-next commitment it has
echoed but not signed.

| | decision | why |
|---|---|---|
| pending slot | exactly one, at `commitIndex + 1` | in-order signing per device: lightning-kmp's dance advances one commitment at a time, so there is never a second honest candidate to track. One slot keeps the accounting a linear history |
| echo-time evaluation | unchanged in kind: `check` plus the policy verdict run before the echo, and the verdict's ledger and consumption go into the pending columns — inside one Room `@Transaction` that re-reads the row and refuses if the signed tip moved since `check` read it | the transaction makes evaluate-and-record atomic, which the review found it is not today (`check` → `commitLedger` → insert can race); the primary key on `(wallet, channelId)` plus the in-transaction re-read degrades two concurrent same-index requests to one acceptance and one refusal, never two commits |
| supersede | a new request at `commitIndex + 1` replaces the pending slot — any feerate change, fail-back or retry — and the old slot's consumption evaporates with it | the wedge was the slot surviving a signature that never happened. Consumption is rolled back by construction: `budgets()` reads `authorised − IcebergBudgetSpend − pendingConsumed`, and the pending part is a column the supersede overwrites |
| sign-time promotion | `maybeSign`, after the quorum and before `partialSign`: the request's `(commitIndex, txid)` must equal the pending slot's, or this device abstains. On success — atomically with `upsertLedger(signedAt)` (:256) — the slot folds into the signed columns and its consumption into `IcebergBudgetSpend` | a device signs at most one transaction per index, and its budget is irrevocably consumed exactly when its share exists |
| repeat | index `== commitIndex` with the same txid signs again (the retransmission rule, `CoSignerPolicy.kt:79-80`); with a different txid it is refused | this device's share for that index is already out; the refusal at :81 now means what it says — *signed*, not echoed |

Why the budget rollback is safe against the settlement-drain cases
`CoSignerPolicyTest.kt` pins (`a spent budget is gone, whatever arrives
afterwards`, :92-99, and the retry cases at :71-89): consumption becomes
irrevocable on the devices that sign, and only pending consumption rolls back.
The double-payment guard survives: any echo quorum of `n-t+1` overlaps the `t`
signers of a completed signature in at least one member (`(n-t+1) + t - n = 1`),
that member consumed irrevocably, and rule 2's budget check refuses the second
attempt on it — so no quorum for a double-payment ever forms. The pure
`CoSignerPolicy` object is untouched; all of this lives in
`GroupLightningCoSigner`.

Why not simply move `commitLedger` into `maybeSign`: then two different
transactions at index `N`, each echoed against the same old ledger, can both
collect `n-t+1` echoes — an honest member in the overlap echoed both, since
nothing at echo time distinguishes them — and, when `n ≥ 2t` (2-of-4 included),
both can gather `t` signing devices whose per-device signing race resolves
differently. Two fully-signed commitments at one index splits the devices' budget
accounting, and the double-payment guard then rests on timing. Keeping the
evaluation at echo time, with supersede restricted to the unsigned slot,
preserves the property that an honest member's accounting advances through one
transaction per index at a time.

Readers to move to the signed columns: the force-close fence (`:345`, which today
reads the *echoed* index — coordinate with `force-close-revoked-commitment.md`,
whose off-by-one fix must be redone against signed-index semantics, and land in
the same release) and `signedOursMsat` (:111-112, which the doc at line 97
already describes as "the last commitment you signed" — this phase makes that
true). The first-commitment path (:399, :431) interacts with
`first-commitment-policy.md`; land that plan first, and this phase's evaluate
step then covers the open path with whatever check it adds.

**Deployment realism.** The migration is automatic on upgrade, but the fix acts
per device: an old-build co-signer keeps echo-time commits and still refuses the
superseding commitment. That is the safe direction — strictly more refusals,
never fewer — but a channel actually recovers from a burned pending slot only
once at least `n-t+1` co-signers run the new build, since the superseding request
needs their echoes. Say so in the release notes, the way Phase 15's rollout
paragraph does. A channel already wedged before the upgrade stays wedged — the
migration cannot know the channel's true state, and trusting the operator's say
is not an option; the recovery is the documented force close and reopen.

**Tests** (in `GroupLightningCoSigningJvmTest.kt`, plus the mainnet mirror
suite):

- `an echoed commitment that never completes does not wedge the channel` — the
  operator requests the settling commitment, lets it time out, then proposes the
  fail-back at the same index; every device echoes and signs it.
- `a budget burned by an unsigned commitment is whole again when it is
  superseded` — after the timeout, the same payment's retry passes rule 2 without
  a new authorisation.
- `a settled payment's budget stays spent once this device signed` — the existing
  settlement-drain pins in `CoSignerPolicyTest.kt` stay green, and a new jvm case
  shows the re-offer refused on signing devices after completion.
- `two requests at one index raced; exactly one was ever signed` — fire two
  different valid requests at the same index concurrently; assert no device
  produced shares for both, and at least one completed.

## What this does not fix

- **One burn per outstanding request.** Phase 2 bounds label-burning to requests
  that actually exist; a member can still kill each in-flight attempt once, now
  named in the transcript. A group whose member does this repeatedly has a
  membership problem, and Phase 14's re-key is the answer to that, not more
  protocol.
- **Nonce poisoning.** `fileNonces` is first-write-wins (:156-163), so a member
  can poison their own contribution for a counter and fail that request's check
  2; the operator skips the counter. Attributed by construction (the row names
  the participant) and capped at the pool's refill rate.
- **Silence.** A member who simply never echoes or never shares stalls the wallet
  exactly as before; that is the `2t-1`-online requirement
  (`docs/group-lightning-wallet.md`, constraint 4), not a bug.
- **Restored devices** with empty ledgers — `restored-device-marks.md`.
- **The operator's documented griefing** (`error` to force the LSP to close,
  choosing when to send) and the force-close-path findings —
  `force-close-revoked-commitment.md`. The mutual-close amount anchor is
  `mutual-close-amount.md`; Phase 4 does not touch `checkClosing`.
- **The exposure of shipping these fixes later.** Mainnet is live behind the
  1M-sat cap today; everything here is liveness, so the cost of shipping late is
  stalled channels, not lost funds.

## Risks and open questions

| risk | why it matters | what to do |
|---|---|---|
| echo-before-request reordering | the Phase 2 gate drops an echo that beats its request to the device, and the quorum then undercounts | the bounded stash in Phase 2; first verify whether the real inbound path reprocesses stored co-signing events on room replay (as `replayStoredMessages` does for ceremonies) — if it does, the stash may be unneeded |
| false-alarm wording | after Phase 2, a member's false echo and a true operator equivocation produce identical evidence | the alarm names the echoing members, not a verdict on the operator; the screen copy should say both possibilities |
| retransmission vs supersede | Phase 4's repeat branch must be the common case; if lightning-kmp ever retransmits a *changed* transaction at the same index, that is a supersede and the old pending slot's consumption rolls back harmlessly — but confirm the `channel_reestablish` path is byte-identical | a regtest assertion in `GroupLightningRegtestJvmTest` that a reconnect retransmits the same txid |
| mixed-build groups | old co-signers refuse superseding requests, so a wedged channel recovers only with `n-t+1` upgraded devices | release notes say so; upgrades roll out per device with no coordination needed, since old behaviour is strictly stricter |
| verifying shares before the request arrives | `fileShare` skips verification when the request is not yet stored, so a fast garbage share is filed unverified | the operator-side verify in `roundTwo` still excludes it; only the attribution line is lost in that race. Acceptable; revisit if the reordering turns out to be common |
| freezing members who double-echo | considered and rejected: an honest member echoes two hashes for one label exactly when the operator equivocated, so freezing echoers punishes the victims | record and name; do not penalise |
