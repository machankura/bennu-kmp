# The group sets the delay on its members' guarded wallets

A member's guarded wallet carries one leaf for the group that guards it and one
for every group above it. Since [guardian-delay.md](./guardian-delay.md), each
ancestor's leaf keeps the delay that ancestor chose when it made the group below
it. The group's **own** leaf, though, is always `GuardianDerivation.DEFAULT_DELAY`,
12 blocks. The member's device builds the grant without a delay
(`MemberWalletManager.kt:193`), and every signer recomputes it the same way and
refuses anything else (`refuseCertificate`, line 518). Nobody chooses the number.

This plan lets the group choose it. **The group's quorum signs one standing
statement in its own room: wallets members ask this group to guard carry this
group's leaf at `d` blocks. A member's device builds its request under the
newest such statement and carries that statement in the request. Every signer
checks that the request's own leaf has exactly that delay, and that the
statement it was built under is the group's newest.** Each wallet's delay is
then fixed in its certificate, as every guardian tag already is. A later
statement changes the wallets members ask for after it, and never one already
guarded.

Read [member-guarded-wallets.md](./member-guarded-wallets.md) and
[guardian-delay.md](./guardian-delay.md) first. This plan reuses the request,
the certificate, the key notice and the signer check from the first, and the
delay range, the formatter and the delay chooser from the second.

**Not built.** Written against the code as it stood at `05bd6ed6`, schema v31.

---

## Where the delay should live

The question this plan has to settle first. There are four places a group-level
number could be signed, and only one of them is the group's own decision about
the group's own leaf.

| place | who signs it | why not |
|---|---|---|
| the group's **birth certificate** (30329) | the **parent's** quorum, once, at the group's birth | it is the parent's statement about the child, and the member-wallet delay is the group's decision about its own leaf. The group did not sign it and cannot change it, because a certificate is replaceable only by the parent, and the group's key state carries the one it was born with. A **root group has no certificate at all**, so the most common group could never set a delay |
| the group's **key state** (30326) | the group's own quorum | it answers one question, *what does this room sign with*, and `GroupKeyStateManager.stateFrom` drops a state that says anything it cannot check. Changing a wallet policy would mean re-signing the room's key state, which every device then re-adopts. The key notice already carries the key, so the delivery advantage is small |
| the group's **wallet statement** (30331) | the group's own quorum | it opens one account on one network, and only once. A group with no wallet of its own, or a group that guards members on signet and mainnet, has nowhere to put it. It is also immutable for its account by the guardians note's rule 2 |
| **a new group-signed event**, 30337 | the group's own quorum | **recommended.** Signed by the group about the group, changeable by the group, and verifiable by anyone holding the room's id, through the same `GroupKeyStateEvent.isSignedByRoom` check the chronicle and the certificates use |

So the answer to "birth certificate or group signed event" is **a group-signed
event of its own kind**. The birth certificate stays the parent's, and keeps
deciding only what the parent decides: whether the group is guarded, and the
parent's own leaf's delay in the group's wallet.

---

## The policy event

```
kind:       30337
pubkey:     G                                       set by the signing session
content:    ""
tags:
  ["d",              "member-wallets"]              addressable: the newest wins
  ["guardian_delay", "<blocks>"]                    6..65,535, GuardianDerivation.DELAY_RANGE
```

- **Signed by `G`'s quorum** through the existing FROST proposal path, like the
  wallet statement. No new signing machinery.
- **Newest wins**, by the group's own `created_at` with the id breaking a tie,
  for the reason `GroupWallet.newestAmong` gives: two devices that read the same
  events in different orders have to agree.
- **No policy means the default.** Every group that never signs one keeps
  guarding members' wallets at 12, exactly as today.
- **One tag today.** The `d` tag names *member wallets*, not *delay*, so a later
  rule about members' wallets is a second tag on the same event rather than a
  second kind.

### Why the request carries it

A member's device needs the delay before it builds anything, because the leaf,
the tree and both descriptors depend on it. Two kinds of device may not hold the
newest policy:

- **A member who joined after it was signed.** MLS cannot decrypt messages from
  before a member's welcome, which is the reason the key notice (30336) exists.
- **A signer who missed it.** `docs/mls-skipped-keys.md` describes how a group
  event that arrives a moment late is dropped for good on one device.

So the request carries, whole, the policy event it was built under. Any reader
can check it with `G` alone. A signer that missed the newest policy then still
judges the request against what the group actually signed. A signer whose newest
is newer than the one carried refuses the request as stale, and the member asks
again. Because the certificate embeds the request, the ancestors can also see
why the leaf has the delay it has.

---

## Phase 0 — decisions, before any code

### D1. A group-signed event, not the birth certificate

**Decided by the table above.** Kind 30337, the next free number after the key
notice at 30336.

### D2. Can the group change it?

**Recommended: yes, for wallets asked for afterwards; never for one already
guarded.** A wallet's delay is in its certificate's guardian tags, and the
guardians note's rule 2 fixes an account's guardians, delays included. So a new
policy reaches only requests built under it. The screen that changes it says so:
*Wallets already guarded keep the delay they were guarded at.*

### D3. What the delay covers

**Recommended: the group's own leaf only.** Each group above keeps the delay it
chose for the group (guardian-delay D1), so a member's wallet under `M`, under
the root, reads:

```
leaf 1  M's own     delay from M's newest 30337, or 12
leaf 2  the root's  the delay the root chose for M, unchanged
```

A group cannot shorten the root's wait in its members' wallets any more than in
its subgroups'.

### D4. One delay per group, not per member

**Recommended: one.** A group that wants to treat one member differently is
asking for a different rule, and a per-member table would be a roster the group
signs over and keeps current. The member still chooses *whether* to ask. If they
do not like the group's delay, they do not have to ask that group.

### D5. Who may set it

**Recommended: any member holding a share proposes, and the group's quorum
decides,** like every other event the group signs. The entrance is shown only to
a device that can sign (`FrostSigningManager.canSign`), as the wallet's *Open*
button is.

### D6. Which request a signer accepts

A signer holds `held`, the newest verified 30337 it has, or none. The request
carries `carried`, or none. **Recommended:**

| carried | held | judged at | otherwise |
|---|---|---|---|
| a 30337 `G` signed | none, or one not newer than it | `carried`'s delay | — |
| a 30337 `G` signed | one newer than it | — | refused: *was asked under a delay the group has since changed* |
| a 30337 not signed by `G`, or unreadable | any | — | refused: *carries a policy this group did not sign* |
| none | none | `DEFAULT_DELAY` | — |
| none | one at `DEFAULT_DELAY` | `DEFAULT_DELAY` | — |
| none | one at any other delay | — | refused: *was asked without the group's delay* |

The last row is how a request from a build before this plan fails under a group
that has set a delay: its member was shown 12, and the group decided otherwise.
That is the right failure. The member's screen said something the group never
agreed to.

### D7. Mixed versions

**A signer on a build before this plan compares to `grantFor` at the default**,
so it refuses any request whose own leaf is not at 12. That is the safe way to
fail: such a group can still guard members' wallets at 12 and at nothing else. A
group should not set a delay other than 12 until every member who can sign is on
a build from Phase 5 onward. The change screen says this, because a device
cannot see other devices' versions.

---

## Phase 1 — the policy event, pure

New `nostr/wallet/MemberWalletPolicyEvent.kt`:

```kotlin
object MemberWalletPolicyEvent {
    val KIND: Kind = 30337
    const val IDENTIFIER = "member-wallets"

    fun template(delay: Long, createdAt: Long = now): EventTemplate   // requires DELAY_RANGE

    data class Policy(val delay: Long, val event: Event) {
        val createdAt: Long get() = event.createdAt
    }

    /** [event] as [chatRoomId]'s policy: the kind, the `d`, a readable delay in range, signed by the room. */
    fun verify(event: Event, chatRoomId: String): Policy?

    /** The newest among [events] that verifies, by created_at then id. */
    fun newestAmong(events: List<Event>, chatRoomId: String): Policy?
}
```

`verify` checks the kind, the `d` tag, exactly one `guardian_delay` tag that
reads and is in `GuardianDerivation.DELAY_RANGE`, and
`GroupKeyStateEvent.isSignedByRoom(event, chatRoomId)`, all inside `runCatching`.

**Tests**, `MemberWalletPolicyEventTest`, pure:
- a policy round-trips;
- each of these fails on its own: another kind, a missing `d`, two delays,
  `"5"`, `"65536"`, `"twelve"`, signed by another room;
- of three signed policies, the newest wins whatever order they arrive in, and
  a tie on `created_at` goes to the larger id on every device.

---

## Phase 2 — the group files it, and the transcript says it

The FROST completion path already files every signed event in
`GroupSignedEvent` (`FrostSigningManager.recordSignedEvents`), whatever its kind.
This phase reads them:

- `MemberWalletManager.policyFor(database, G): MemberWalletPolicyEvent.Policy?`
  is the newest verified among `G`'s stored 30337s and any carried in key-notice
  answers (Phase 3). It is the one reader, used by the member's device in Phase 4
  and by every signer in Phase 5.
- `ChatMessage.applyInnerEvent` and `applySignedEvent` get a 30337 arm that
  writes one transcript line: *The group will guard members' wallets after 144
  blocks, about 1 day*. Without the arm an older build shows a raw-JSON bubble,
  which is reason to ship this phase before Phase 6's entrance.
- `ProposedEvent.summarize` gets a 30337 arm: *Members' guarded wallets ·
  this group after 144 blocks, about 1 day · for wallets asked for from now on*.

**Tests**: `policyFor` prefers the newest of two stored policies; ignores one
signed by another room; returns null for a group that never signed one. The
summary says the delay in both blocks and time.

---

## Phase 3 — the key notice carries it

A member who joined after the policy was signed cannot decrypt it. The key
notice already answers that member's other question, *what does this group sign
with*, so it answers this one too:

- `GroupKeyNoticeEvent.answeringTags` gains
  `["member_wallet_policy", <the newest 30337, whole>]` when the answering device
  holds one.
- `read` returns it in `GroupKey.policy`, verified by
  `MemberWalletPolicyEvent.verify(it, G)`. A policy present and unreadable makes
  the whole answer unreadable, as an unreadable certificate already does. It is
  never read as "no policy".
- `MemberWalletManager.groupKeyFor` gains the policy, and `policyFor` considers
  it beside the stored ones.

An answer from a device that holds no policy says nothing about one, so a late
member may build under the default while the group has a delay. Phase 5's D6
table refuses that request, and the member's request row says why: *ask again
once the group's delay has reached this device*. The next answer from a device
that does hold the policy fixes it.

**Tests**: an answer carrying a policy gives it; one carrying a policy signed by
another room does not read; an answer without one reads, with no policy.

---

## Phase 4 — the member builds under it

In `MemberWalletManager.request`:

```kotlin
val policy = policyFor(database, chatRoomId)
val grant = GuardianDerivation.grant(
    groupKey.thresholdPublicKey, chatRoomId.lowercase(), groupKey.guardians, walletId,
    delay = policy?.delay ?: GuardianDerivation.DEFAULT_DELAY,
)
```

- `MemberWalletRequestEvent` gains `["member_wallet_policy", <the 30337, whole>]`
  when there was one. The member's nostr signature covers it, as it covers
  everything in the request.
- `Reading.verified()` gains a sixth check: a carried policy verifies against the
  request's `h`, and the first guardian's delay equals the policy's, or equals
  `DEFAULT_DELAY` when nothing is carried. This is everything a reader can check
  without knowing the group's newest. The newest is Phase 5's job.
- `MemberWalletManager.delaysOf` uses the policy for the first delay. The
  new-wallet screen already lists each guardian's delay from it (since
  `3b2d9bd8`), and gains *set by #Council* beside the group's own line.

**Tests**: a request under a policy at 4,320 has its first guardian at 4,320 and
carries the policy; a request whose first delay disagrees with its carried
policy fails `verified()`; one that carries no policy and has a first delay of
12 passes.

---

## Phase 5 — every signer checks it

`MemberWalletManager.refuseCertificate` stops computing the expected grant at
the default. Instead it:

1. reads the request out of the certificate, as it already does;
2. applies D6's table, with `held = policyFor(database, G)` and `carried` from
   the request, to get the delay or a reason to refuse;
3. compares the certificate's guardians to
   `SubgroupManager.grantFor(database, G, K_G, W, delay)`, which since
   guardian-delay Phase 6 takes the own leaf's delay. The keys, their order and
   every inherited delay are checked exactly as before.

`MemberWalletManager.putToGroup` runs the same check before it proposes, as it
already runs `refuseCertificate`. A sponsor is then never asked to put to the
quorum a request their own device knows is stale.

The member's request row on the group page (*Asked by @alice, not yet signed*)
reads the same reasons. A stale request says *asked under an older delay* and
offers the member *Ask again*, which builds a new request under the newest
policy, with a new key. The old request's key is discarded, because no address
was ever offered.

**Tests**, `MemberWalletSigningJvmTest`: every row of D6's table, through
`refuseCertificate`; a signer that missed the newest policy accepts a request
carrying it; two signers with different held policies agree once the request
carries the newer one.

---

## Phase 6 — the group sets it

**The entrance.** On `ChatRoomDetailScreen`, in *Members' guarded wallets*, a
first row for every member:

```
Guardian delay                     144 blocks, about 1 day
for wallets members ask this group to guard            [Change]
```

*Change* is shown only where the device can sign (D5). It opens
`MemberWalletDelayScreen`, a pushed screen with a `NavigateBackButton`, which
holds:

- `GuardianDelayChooser`, **moved out of** `SelectSubgroupAdminsScreen` into
  `ui/composable/widgets/GuardianDelayChooser.kt`, with its presets, its custom
  field and its live `delayText`. Its words are passed in, because *the
  subgroup* and *the member* differ;
- D2's line: *Wallets already guarded keep the delay they were guarded at*;
- D7's line: *Members whose app is older than this one can only be guarded at
  12 blocks*;
- *Ask the group*, which proposes the 30337 through
  `FrostSigningRepository.proposeSigning` and goes to `FrostSigningRoute`, as
  opening a wallet does.

A view model, `MemberWalletDelayViewModel`, holds the input as typed, refuses
a delay below 6 or above 65,535 blocks with the reasons the subgroup picker already
uses, and does not propose the delay the group already has.

The member's own leaf is the group's, and the trade the subgroup picker states
applies here too, said for a person: *A coin the member moves starts waiting
again, so a long delay lets them keep their coins out of reach by moving them.*

CLAUDE.md applies in full: strings in the catalogue in sentence case, spacing
from `MaterialTheme.spacing`, the screen's four states, and the outcome reported
through `rememberNotifier`.

**Tests**, `MemberWalletDelayViewModelJvmTest`: it starts at the group's current
delay; a preset and a typed delay are what it proposes; out-of-range delays are
refused with their reasons; proposing the current delay is refused.

---

## Phase 7 — docs, and checking it on the emulators

- [member-guarded-wallets.md](./member-guarded-wallets.md): *What "full control"
  means* stops saying 12 and links here. The request's tag list gains the policy.
- [guardian-delay.md](./guardian-delay.md): its build table's member-wallet row
  ("a group still cannot choose…") points here.
- [README.md](./README.md): this note's row.
- This note: *Built*, and a table of where the build chose differently.

By hand, regtest, three emulators: Alice (a signer), Bob (a signer), and Carol,
who joined the group after step 1.

1. Alice and Bob set the group's member-wallet delay to 144 blocks. The
   transcript says so on all three devices that were in the room, and not on
   Carol's.
2. Carol opens *New guarded wallet*. Her device asks for the key; the answer
   carries the policy; her screen says *after 144 blocks, about 1 day*.
3. Bob puts Carol's request to the group. Both signers accept it, and the
   certificate's first guardian tag says 144.
4. Alice and Bob change the delay to 1,008. Carol's wallet still says 144. A new
   request from Carol is built at 1,008.
5. A request built on an older build (12, no policy) is refused by both signers,
   with *was asked without the group's delay*.

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 1 | `MemberWalletPolicyEvent` and its test | dark: a kind nothing produces |
| 2 | `policyFor`, the transcript and summary arms | dark, and should ship early: an older build shows a raw-JSON bubble for a 30337 it has no arm for |
| 3 | the key notice | dark |
| 4 | the request | dark: no group has a policy yet, so every request is at 12 |
| 5 | the signers' check | dark, and before 6: it has to be on signers' devices before any group can set a delay |
| 6 | the entrance and the screen | **the switch** |
| 7 | docs and the emulator run | last |

No schema change. Policies are `GroupSignedEvent` rows like every other event
the group signs, and the request already keeps its event whole.

---

## What this does not do

**It does not change a wallet already guarded** (D2). A different delay is a new
request, with a new key.

**It does not let the member choose.** The member chooses whether to ask, and
sees the delay before they do. A member who wants a longer delay than the group
offers asks a different group, or asks this one to change it.

**It does not set the subgroup picker's default.** A group making a guarded
subgroup still chooses that subgroup's delay on the picker, starting from 12.
Using the member-wallet policy as the picker's starting point is a one-line
follow-up, and it is left out so that the policy means one thing.

**It does not reach a group's ancestors.** The root's delay in a member's wallet
under `M` is the delay the root chose for `M` (D3). A root that wants a
different wait in its descendants' members' wallets sets it when it makes the
group below it.

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| a tag on the group's birth certificate | the parent's statement, not the group's; fixed at birth; absent for a root group (see *Where the delay should live*) |
| a tag on the group's key state | re-signing what the room signs with to change a wallet rule, and every device re-adopting it |
| a tag on the group's wallet statement | one per network and account, immutable, and absent for a group with no wallet of its own |
| the member chooses, within a range the group sets | asked for as *the group sets the delay*; a range is a second number to sign and explain, and can be added later as a second tag on the same event |
| signers judge only by the policy they hold | a signer that missed the newest policy would refuse a correct request, and two signers holding different ones would never agree on the same request |
| a per-member delay | a roster the group signs and keeps current (D4) |
