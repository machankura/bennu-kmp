# Signer hygiene: five small findings and their fixes

Five small things the review found around the Iceberg ceremony and the co-signing
path of the [group Lightning wallet](../group-lightning-wallet.md). None of them
moves money on its own; each one erodes a property the design states. They share
this plan because each is a day or two, except two: the fork's
`fullySignedCommitTx` crash path (Phase 3) and the signing-deadline measurement
(Phase 5), which carry the real work.

Read the design doc first; every phase below cites it where the fix touches one
of its decisions.

---

## The finding

### (a) A failure broadcast can carry decrypted contribution bytes

In `IcebergRitualManager.revealsTo`, the JSON decode of the *decrypted* round-2
plaintext sits outside the try/catch that wraps the decryption:

- `IcebergRitualManager.kt:392-396` — the `Nip44.decrypt` is wrapped, and a
  failure becomes `Fault.MalformedMessage(j, "its contributions could not be
  decrypted")`, a fixed string.
- `IcebergRitualManager.kt:397` — `CommonJson.decodeFromString(Round2Plain.serializer(), plain)`
  is *not* wrapped. kotlinx's `SerializationException` message includes a
  `JSON input: …` snippet of the offending payload.

The exception propagates to `advance`'s catch-all
(`IcebergRitualManager.kt:364-368`), which does two things with `e.message`:
stores it in the session's `failureReason` (`:366`), and **broadcasts it
room-wide** as the content of kind 30354 `CEREMONY_FAILURE` (`:367`). The leak
then propagates a second hop: `record` files a received failure as
`"Abandoned by ${sender.take(8)}: ${innerEvent.content}"` (`:280`), so every
other device copies the snippet into its own `failureReason`.

The scenario, step by step:

1. An honest member's device sends a malformed round-2 payload to this device —
   version skew between builds, a truncated MLS write, a hand-built bug. The
   ciphertext is fine; the *plaintext* does not parse.
2. This device's decode throws at `:397` with `JSON input: …` carrying a snippet
   of the plaintext, which is exactly the seed contributions `c[j,T]` that
   NIP-44 exists to keep between holders of `T` (design doc Phase 5, line 67).
3. The snippet is stored locally and broadcast to the room at `:367`, then
   copied into every member's `failureReason` at `:280`. Non-holders of `T` now
   hold real contribution bytes.

One contribution never reconstructs a seed — a seed is the hash of all
`n-t+1 ≥ t` holders' contributions, and at most `t-1` members are corrupt — so
the threshold property survives. What erodes is the per-seed secrecy margin the
commit-reveal scheme was built to keep.

The other producers were checked: `:280` and `:366` are the only two writing
`failureReason`. The round-1 and round-3 decodes (`:377`, `:338`) throw the same
way, but their payloads are room-visible MLS inner content, so a snippet leaks
nothing new; only `:397` decodes bytes that were secret a line earlier. The same
broadcast pattern exists in the onchain ceremony (`ChillDkgRitualManager.kt:933-948`);
what a snippet of *its* payloads leaks is the onchain wallet's question, not this
plan's — the vocabulary helper below is shaped so that manager can adopt it later.

### (b) The label assignment is persisted after it is handed out

The fork's contract on `IcebergGroupTransport.nextFreshLabel`
(`RemoteIcebergFundingSigner.kt:58-62`): *"The transport persists the counter
BEFORE returning, because a label handed out twice signs two messages, which
leaks a share and raises no error."*

`GroupIcebergTransport.assign` (`GroupIcebergTransport.kt:100-111`) does the
opposite: it increments `nextCounter` in memory, updates the in-memory
`assignments` map, and only then persists in a fire-and-forget `scope.launch`
(`:105-108`), swallowing failures with `runCatching`. `insertAssignment` is
`OnConflictStrategy.ABORT` (`IcebergCoSigningDao.kt:27-28`), so a conflict is a
thrown, logged, forgotten exception.

The scenario:

1. The node hands counter 5 to a new `VerificationNonceId` and returns its label
   to the channel. The persist coroutine has not run.
2. The process dies. On restart, `reload` reads `highestAssignment` = 4
   (`GroupIcebergTransport.kt:90`), so `nextCounter` = 5 again.
3. A different need — a different funding txid under negotiation — is handed
   counter 5. Two purposes, one label. If the group is asked to sign under both,
   the label has signed two messages.

Today this degrades to liveness, not a share leak, because every co-signer's
ledger is first-write-wins on `(wallet, label)` (`IcebergCoSigningDao.kt:39-41`,
never pruned) and refuses the second message — the class doc argues exactly this
(`GroupIcebergTransport.kt:49-51`), and the design doc accepts the residual cost
("A crash that loses an assignment costs a force close's signature, never a
share", line 78). But the contract the fork states is not met, and the guard
that saves us lives on other people's devices.

Every other assign/pool write was checked for the same pattern: the co-signer's
inserts (`GroupLightningCoSigner.kt:136,162,210`) are awaited inside suspending
functions, and the transport's other `scope.launch` calls (`:117`, `:192`) are
retries, not ordering-sensitive persists. `assign` is the only violation. One
incidental drift: `IcebergLabelAssignment.purpose`'s comment
(`IcebergCoSigning.kt:30`) says `fresh:<funding txid>`; the code writes
`fresh:<counter>` (`GroupIcebergTransport.kt:106`). The comment is wrong.

### (c) `fullySignedCommitTx` assumes the signature cannot fail

`Commitment.fullySignedCommitTx` (fork `Commitments.kt:285-306`) signs our own
commitment for publishing. The taproot branch does:

```kotlin
val localSig = fundingSigner.signWithVerificationNonce(...).right!!   // Commitments.kt:301
val signedTx = unsignedCommitTx.aggregateSigs(...).right!!            // Commitments.kt:302
```

`Either.Left.right` is `null` (`Either.kt:41`), so a refused signature is a
`NullPointerException` inside the channel state machine. The comment above it
— *"we have already validated the remote nonce and partial signature when we
received it, so we're guaranteed that the following code cannot produce an
error"* (`:298-299`) — is true for a private key and false for
`RemoteIcebergFundingSigner`, whose answer is a room of phones with a policy.
The design doc acknowledges the path and defers it: *"the attempt throws in the
channel … left for the review of Phase 13"* (line 110).

The scenario, step by step:

1. The LSP sends `error` — the griefing path the design expects
   (`handleRemoteError`, `Channel.kt:256-280`) — or lightning-kmp's own
   automatic handling fires: an incoming HTLC we fulfilled downstream is about
   to time out, so `checkHtlcTimeout` (`Channel.kt:786-807`) force-closes at
   `:803`, or any other `Throwable` lands in `handleLocalError`'s `forceClose`
   (`Channel.kt:209-212`, reached for `Normal`, `ShuttingDown`, `Negotiating`,
   `WaitForChannelReady` at `:220-235`).
2. All of these call `spendLocalCurrent` (`Channel.kt:746-780`), which calls
   `fullySignedCommitTx` (`:756`).
3. The signature request goes to the group as `LOCAL_COMMITMENT`. The
   co-signers refuse: signing our own commitment needs a FROST force-close
   authorisation, and there is none (`GroupLightningCoSigner.kt:341`).
4. The transport's `roundTwo` collects nothing; `withTimeoutOrNull(deadline)`
   — 60 seconds, during which the whole peer event loop is blocked — returns
   `Either.Left(IcebergSigningTimeout)` (`RemoteIcebergFundingSigner.kt:199`).
5. `.right!!` throws. If the entry point was `handleRemoteError` or
   `checkHtlcTimeout` (inside `processInternal`), the throw is caught by
   `ChannelState.process` (`Channel.kt:68-75`), which calls `handleLocalError`,
   which calls `forceClose` → `spendLocalCurrent` → **another 60-second wait and
   a second throw — this one inside the catch handler, so nothing catches it**.
6. The exception escapes `Peer.process` (`Peer.kt:263-280`) and `processEvent`,
   and kills `run()` (`Peer.kt:1177-1184`), which has no try/catch. The peer's
   event loop is dead: no messages, no watches, no reconnect handling, until
   the node is restarted. On restart the channel is still `Normal`, the same
   event recurs, and the loop dies again.

No funds move the wrong way — the refusal is the co-signers doing their job, and
if the LSP force-closes, D5 pays the group. What breaks is the node, at exactly
the moment (an LSP error, an HTLC timeout) it is needed. There is also a
repeat-signature corner: after a *successful* authorised force close,
`Closing`'s watch handler re-runs `spendLocalCurrent` when our commit is seen
spent from outside (`Closing.kt:109`); the co-signers' `signedAt` gate refuses
the identical re-request, so that path times out and throws too — into
`handleLocalError(Closing)`, which is a safe no-op once `localCommitPublished`
is stored (`Channel.kt:237-247`), but only then.

### (d) Missing negative tests

Two refusals the design relies on have no test:

1. **`t-1` shares must not aggregate.** `IcebergTestsCommon.kt` pins
   sufficiency — exactly `t=2` shares aggregate (`:108-113`) — and the `2t-1`
   nonce quorum (`:127-131`), but never asserts that `t-1` signature shares
   *fail*. The count floor lives in the C library
   (`iceberg.md`'s failure table), reached through one Kotlin `when`; a
   refactor that off-by-ones it fails no test.
2. **The stale-term ex-operator.** `nobody answers a member who is not the
   operator` (`GroupLightningCoSigningJvmTest.kt:265-275`) covers a
   never-elected member only. The elected-then-replaced operator — whose
   requests carry a *real but past* term and a sender the group once trusted —
   is untested. Term fencing is the fence the whole handover argument stands on
   (design doc, "The operator's life cycle"): co-signers refuse anyone but the
   current term's operator (`GroupLightningCoSigner.kt:183-187`).

### (e) The signing deadline is an unmeasured guess

`SIGNING_DEADLINE = 60.seconds` (`GroupLightningNode.kt:401-402`, feeding
`RemoteIcebergFundingSigner.deadline` at `:192`). The comment says it: *"Phase 0
was to measure what the LSP tolerates; until it is, a minute."* Phase 0 step 2 —
how long the LSP waits between its `commit_sig` and our `revoke_and_ack` and
`commit_sig` — was never run (design doc lines 32 and 50). The deadline decides
two live questions: whether a phone quorum can answer in time (constraint 4 and
the first risks row), and what a timeout *costs* — a burned label, and whatever
the LSP does to a channel whose answer never came.

Two things have changed since Phase 0 was written. The fork has no `jvmTest`
Node program to reuse (no `fun main` exists under `modules/core/src`); the
harness is to be written, not found. And Phase 15 went mainnet-only, so the
testnet3 LSP the step names (`Lsp.kt:50-58`, in `lightning-kmp-app/library`) is
no longer a network the app offers — see the open questions.

---

## Root cause

One root for (a) and half of (b): **a contract stated in a comment, enforced
nowhere.** The fork states the persist-before-return rule in a doc comment; the
app's only implementation breaks it, and no test could tell. The ceremony
manager's rule — a failure broadcast is read by people who hold none of the
secrets — was never stated, so a convenient `e.message` became the broadcast
content.

One root for (c): **an upstream invariant stopped being true when the seam moved
onto the network.** lightning-kmp's `.right!!` encodes "a local key cannot
refuse". The fork made signing remote, fallible and slow, and updated every
call site that returns an `Either` (`Commitments.kt:208-210`, `:613-616` map a
refusal to `InvalidCommitNonce`) — except this one, whose return type
(`Transaction`, not `Either`) predates the seam and whose callers are the
force-close paths.

And one root for (d) and (e): **the two checks that were deferred to "later"
were never scheduled.** The review that was to gate Phase 15 is what found them.

---

## The fix, in phases

Each phase is independent; the order below is by value per day. Fork changes —
Phase 3, and the harness in Phase 5 — land on the `claude/group-lightning-wallet`
branch in `lightning-kmp-app` and in `experimental/lightning-kmp`, as every
library phase has (design doc line 125). Phases 1, 2 and 4 are app-only.

### Phase 1 — the failure broadcast carries a fixed vocabulary

**About a day. App only; no schema, no wire-kind change.**

1. **Wrap the decode.** `IcebergRitualManager.kt:397` moves inside the try at
   `:392-396`, and a decode failure throws
   `Fault.MalformedMessage(j, "its contributions could not be read")` — the same
   shape as the decrypt failure beside it, blamed by index, carrying nothing of
   the payload.
2. **Sanitize what leaves the device.** A private `sanitized(e: Throwable): String`
   maps the known faults to fixed strings: `MissingCommitment`/`MissingReveal`/
   `BadReveal`/`MalformedMessage` name their participant index and nothing else,
   `InconsistentShares` drops the library's `reason`, and any other `Throwable`
   becomes `"Ceremony failed"` — the detail stays in the local log, which already
   has the full exception (`:365`). Both the store at `:366` and the broadcast at
   `:367` carry only `sanitized(e)`. Local storage is sanitized too, not just the
   broadcast: a `failureReason` is shown on the ceremony screen.
3. **Cap the sender's contribution.** `:280` currently embeds the *sender's*
   content verbatim, so a member can already write arbitrary text into every
   device's `failureReason`. It becomes a fixed string naming the sender —
   `"Abandoned by ${sender.take(8)}"` — and the content is logged, not stored.
   Truncating instead would still store their text; the fixed string is the
   whole fix.
4. While in the file, correct the `IcebergLabelAssignment.purpose` comment
   (`IcebergCoSigning.kt:30`) to `fresh:<counter>`.

The 30354 content is opaque text to its readers, so no device needs the new
build to *parse* anything. But the leak is a property of the sending device: a
member on an old build still broadcasts its raw `e.message`, so the group only
gets the protection once every participant's device carries the fix. This is
worth one line on the ceremony screen's failure copy only if it proves
confusing; otherwise the transcript line already says the ceremony can be
re-run.

### Phase 2 — the assignment is persisted before the label exists

**About two days. App only; no schema change, one snapshot convention.**

`assign` cannot simply become suspending: it backs the fork's synchronous
`verificationLabel` and `nextFreshLabel`, called from the non-suspending
`verificationNonce` and `publishedNonceSession` (`RemoteIcebergFundingSigner.kt:142,166`),
the second of which is reached from `InteractiveTxSession`'s secondary
constructor — the fifty-one call sites the design doc gives as the reason these
two methods stayed synchronous (line 53).

| | decision | why |
|---|---|---|
| P2-a | **Reserve ahead, awaited.** `reload()` persists reservations for the next `POOL_WINDOW` (64) counters past every reserved one, before any of them can be handed out | the persist is then always *before* the hand-out, which is the whole contract; `reload` is suspending and already runs at `create`, at every `provision`, and every two seconds in the refresher |
| P2-b | `assign` hands out only a reserved counter; past the frontier it throws `IcebergNonceNotProvisioned` | matches the existing "unprovisioned label throws" rule (design doc line 53); the node's start gate already refuses to connect short of pool (`awaitPool`), so the throw is unreachable in a node that passed its gate |
| P2-c | the purpose back-fill stays asynchronous | a crash that loses it burns one verification label — the cost the design doc already accepts (line 78); safety never depends on the purpose |

Concretely:

- `GroupIcebergTransport.reload()` gains a reservation step: after reading
  `highestAssignment`, it inserts `IcebergLabelAssignment(key, counter, label,
  purpose = "reserved")` for every unreserved counter up to `nextCounter +
  POOL_WINDOW`, awaited. The insert becomes `OnConflictStrategy.IGNORE` —
  reservation is idempotent; today's `ABORT` plus `runCatching` is how a
  conflict silently became a log line. The entity is unchanged: the `purpose`
  string carries the convention, so there is **no schema migration**.
- `assign(purpose)` takes the lowest reserved counter at or past `nextCounter`
  and never one past the frontier; a verification purpose launches its back-fill
  as today. `highestAssigned` and `assignments()` (`:75-78`) — which the snapshot
  carries to the next operator (design doc line 98) — report only assigned rows,
  so an old build reading a snapshot is unaffected, and a new operator re-reserves
  its own frontier on `create`. Burned reservations waste the round-one
  contributions published for those counters: room traffic, not money.
- The frontier throw (P2-b) re-enters the channel loop as a failed round one,
  which is why this phase lands after or beside Phase 3: today an
  `IcebergNonceNotProvisioned` out of `verificationNonce` inside
  `spendLocalCurrent` is finding (c)'s uncaught throw. With Phase 3 in, it is a
  refused signature and a disconnect.

### Phase 3 — a refused local-commitment signature is a failure, not a crash

**About four days, in the fork. The phase to be careful with.**

1. **Return the failure.** `Commitment.fullySignedCommitTx`
   (`Commitments.kt:285`) returns `Either<ChannelException, Transaction>`. Both
   `.right!!` go: a `Left` from `signWithVerificationNonce` becomes a new
   `ChannelException` (`CannotPublishLocalCommitment(channelId, cause)` beside
   `InvalidCommitNonce` in `ChannelException.kt`), and a `Left` from
   `aggregateSigs` the same. The segwit-v0 branch is untouched — its
   `privateKey(...)` throw is already loud and unreachable for group channels.
2. **The callers decide.** `spendLocalCurrent` (`Channel.kt:746`) returns
   `Either<ChannelException, Pair<…>>`. On `Left`:
   - from `handleLocalError.forceClose` (`:209-212`) and `handleRemoteError`
     (`:272`, `:276`): log the cause, send the `Error` to the peer as today,
     **leave the state as it is and disconnect** — do not retry, do not abort
     (abort deletes the channel). The LSP will force-close on its own schedule
     and D5 pays the group; the operator node's redial loop
     (`GroupLightningNode`, design doc line 89) owns liveness. This is the
     design's own rule — "the signer returns `IcebergSigningTimeout` and the
     channel treats it as any failed signature" (line 55) — applied to the one
     path that instead threw.
   - from the watch paths (`:538`, `:547`; `Closing.kt:87`, `:109`): stay put.
     A watch already told us the truth about the chain; the re-sign was for
     claiming outputs, and a group channel's claims are built elsewhere or not
     at all (the `Helpers.kt` guards).
   - from `checkHtlcTimeout` (`:803`): as `handleLocalError` — send the error,
     stay, disconnect. What this *costs* when the group has not authorised a
     force close (a fulfilled HTLC timing out upstream) is a policy question,
     and it belongs to [force-close-revoked-commitment.md](force-close-revoked-commitment.md),
     not to this phase.
3. **No retry loop.** Nothing in `Channel.kt` may call `spendLocalCurrent` again
   as a reaction to its `Left`; that is how the 60-second double wait happened.
   A retry, when wanted, is the group's: an authorised force close asks the
   co-signers under the same verification label, whose re-request the ledgers
   accept as the same `(label, message)` — the `signedAt` gate's repeat
   behaviour for an *identical* message needs pinning by a test (Phase 4),
   because `Closing.kt:109` depends on it.
4. **Call sites.** The `commonTest` callers (`TestsHelper.kt:113`,
   `SpliceTestsCommon.kt`, the Iceberg and Prefractal channel suites) unwrap the
   `Either`. `Commitments.fullySignedCommitTx` (`:684`) propagates it.

Fork-only plus its tests: no app change, no schema, no wire kind. Only the
operator's device runs this code — co-signers never see it — but since any
participant can be elected operator for the next term, every device should
carry the build before the group relies on the new behaviour, as with every
fork phase (design doc, Phase 15).

### Phase 4 — the two missing negative tests

**About a day.**

1. **`t-1` does not aggregate**, in `IcebergTestsCommon.kt` beside
   `invalid inputs` (`:117`): with the 2-of-4 group, one signature share;
   assert `Iceberg.aggregatePartialSignatures(listOf(it), 4, 2).isLeft`. Repeat
   at 3-of-7 with two shares. This pins the count floor only: the C suite itself
   notes that at exactly `t` a tampered share *passes* aggregation, and the
   backstop there stays `RemoteIcebergFundingSigner`'s session verify
   (`RemoteIcebergFundingSigner.kt:205`) — worth one comment in the test so the
   next reader does not over-trust the floor.
2. **A past term's operator is refused**, in `GroupLightningCoSigningJvmTest.kt`
   beside the impostor test: build the group, then FROST-sign a second operator
   statement at term + 1 naming a different member (the fixture's `sign` helper,
   `GroupLightningFixture.kt:134`), pump it to every device, and drive the *old*
   operator's transport — term 1, real operator, real pool. Every device's
   ledger stays empty and no share is filed. Name it to sit beside its
   neighbours: `an operator of a past term is refused by every member`. The
   mainnet suite (`GroupLightningCoSigningMainnetJvmTest`) re-runs the cases
   class, so it comes for free.
3. While there, pin Phase 3's repeat rule: an identical re-request under a
   verification label already signed — the `Closing.kt:109` case — gets an
   answer, and a *different* message under it never does (the existing
   `a label is never answered under a second message` at `:303` covers the
   fresh-label half).

### Phase 5 — measure what the LSP tolerates

**About three days, plus testnet3 coins. The harness is fork work; the number
lands in the app.**

1. **The harness.** Write the jvmTest Node-style program the design doc's
   Phase 0 assumed existed: a JVM main in the fork (on
   `claude/group-lightning-wallet`) that builds a `Peer` with a
   `PrivateKeyFundingSigner` wrapped to sleep a chosen duration before each
   answer, connects to the LSP from `Lsp.kt`, and opens a channel by on-the-fly
   funding, as the mainnet run of 2026-10-26 did with the group's node
   (design doc line 90).
2. **What to measure.** Grow the sleep until the LSP acts: the largest delay
   between its `commit_sig` and our `revoke_and_ack` + `commit_sig` that it
   tolerates, and — as important — *what it does* past it. Disconnect and retry
   means a timed-out label costs a reconnect. Fail the channel means it costs a
   force close, and the deadline's meaning changes with it. Measure with pings
   answered outside the channel loop, as the code now stands (design doc line
   54); the with/without-pings comparison the phase originally asked for is
   moot. Repeat a handful of times; one LSP's patience is a policy, not a
   constant.
3. **Where the number lands.** `RemoteIcebergFundingSigner.deadline` stays a
   parameter; `GroupLightningNode.SIGNING_DEADLINE` becomes the measured
   tolerance minus a margin for the quorum's own latency — the room's MLS
   round trips plus the slowest of `2t-1` phones, which the regtest and mainnet
   runs put at 0.4–0.9 s per signature on emulators and seconds on real rooms
   (line 90). The deadline is per-operator and invisible to co-signers, so this
   change needs no group-wide upgrade.
4. **If it is under what a phone quorum takes.** The design doc already says
   what: state it on the ceremony screen — the wallet needs devices that stay
   on — and a desktop co-signer is the realistic third device for a 2-of-4
   group (risks table, line 1036). Whether a tighter tolerance changes the
   offered configurations is the group's decision, not this plan's.

---

## The tests, together

| test | suite | pins |
|---|---|---|
| `a malformed reveal is blamed, and the failure it broadcasts carries nothing of it` | `IcebergCeremonyJvmTest.kt` | Phase 1: a corrupted round-2 plaintext fails the ceremony with `MalformedMessage`; the plaintext hex appears in no 30354 content and no device's `failureReason` |
| `a failure received from the room is stored as a fixed string` | `IcebergCeremonyJvmTest.kt` | Phase 1: a hand-built 30354 with hostile content lands as the fixed string |
| `an assignment survives a restart, and a crashed hand-out burns one counter` | co-signing suite | Phase 2: hand out a label, kill the transport before the back-fill, recreate on the same database; the same need re-derives its label, the next need gets a new counter |
| `a refused local-commitment signature does not kill the peer` | fork `IcebergChannelTestsCommon.kt` | Phase 3: a signer answering `Left`, an LSP `error`, and the channel returns a state and an `Error` action instead of throwing |
| `a watch re-spend of our published commit does not throw` | fork `IcebergChannelTestsCommon.kt` | Phase 3's `Closing.kt:109` corner |
| `fewer than t shares do not aggregate` | fork `IcebergTestsCommon.kt` | Phase 4 |
| `an operator of a past term is refused by every member` | `GroupLightningCoSigningJvmTest.kt` (and mainnet) | Phase 4 |
| the repeat-signature rule under one verification label | `GroupLightningCoSigningJvmTest.kt` | Phases 3–4 |

Gates: the fork's suite (993 tests) plus the new fork tests;
`./gradlew :composeApp:jvmTest` (1,415+) for the app side; and the regtest run
(`testing/regtest/regtest.sh up`, then `GroupLightningRegtestJvmTest`) once
Phases 2 and 3 are in, because both touch paths the run exercises end to end.

## What this does not fix

- **A device on an old build still leaks** (Phase 1) and still hands out labels
  ahead of their persist (Phase 2). Both fixes are per-device; the group has the
  property when every participant carries the build.
- **Who may send `CEREMONY_FAILURE`.** `record` accepts it from any room member
  (`IcebergRitualManager.kt:279-282`), so anyone can kill a ceremony. That is a
  one-member griefing, and it belongs to [griefing-surface.md](griefing-surface.md).
- **The force-close policy itself.** Phase 3 makes the channel *fail cleanly*
  when the group will not sign; whether and when the group *should* sign — the
  revoked-commitment fence, the one-behind rule, the HTLC-timeout case — is
  [force-close-revoked-commitment.md](force-close-revoked-commitment.md). The
  mutual-close amount check is [mutual-close-amount.md](mutual-close-amount.md);
  the first commitment's skipped policy is
  [first-commitment-policy.md](first-commitment-policy.md).
- **The co-signer-side restore gap.** A restored co-signer's empty ledger is
  [restored-device-marks.md](restored-device-marks.md); Phase 2 here is the
  operator-side ledger only. The seed's in-flight exposure is
  [node-seed-in-flight-exposure.md](node-seed-in-flight-exposure.md).
- **A share is still never re-verified on receipt** and a bad one is
  unattributable even though `Iceberg.verifyPartialSignature` exists — liveness
  only, noted by the review, not scheduled here.

## Risks and open questions

| risk | why it matters | what to do |
|---|---|---|
| the LSP fails the channel on a slow signature | every burned label becomes a force close, and Phase 3's clean failure becomes the common path | Phase 5 measures the behaviour, not just the number; report both |
| testnet3 is no longer offered (Phase 15) | Phase 0's step names the testnet3 LSP, but the deadline governs mainnet channels | run the measurement against ACINQ's mainnet LSP with a minimum-size channel, or keep testnet3 as the LSP-behaviour proxy — a product decision, made before Phase 5 starts |
| `assign` throwing past the reservation frontier | an unprovisioned label inside `spendLocalCurrent` is finding (c)'s uncaught throw until Phase 3 lands | land Phase 3 first, or gate Phase 2's throw behind the pool minimum the node already enforces |
| kotlinx's `JSON input: …` snippet is version-dependent | a sanitizer that pattern-matches the message breaks quietly on an upgrade | Phase 1 never parses the message; it maps exception *types* to fixed strings |
| the repeat-signature rule is unpinned today | `Closing.kt:109` re-asks for a signature the ledgers may refuse after a successful force close | Phase 4's third test decides the expected behaviour before Phase 3 relies on it |
| reserved-counter waste on handover | a new operator burns the old one's reserved window, and the round-one contributions published for it | bounded by `POOL_WINDOW`; provisioning refills itself. Not worth a migration |
