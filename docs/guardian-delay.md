# The parent sets the delay: how long a coin waits before a guardian can take it

Today every leaf in a guarded wallet is `<L> OP_CHECKSIGVERIFY 12
OP_CHECKSEQUENCEVERIFY`: any ancestor can take a coin once it has 12
confirmations, about two hours. Twelve is one constant,
`GuardianDerivation.ANCESTOR_DELAY`, the same for every ancestor at every
distance. This plan lets a group choose that number for its own leaf when it
makes a guarded subgroup. The floor is **6 blocks**. The ceiling is **65,535
blocks**, the largest relative lock BIP68 can express in blocks. Everywhere the
app shows a delay, it also shows roughly how long that is in minutes, hours,
days, months or years, whichever reads best.

The headline: **the parent chooses its own leaf's delay on the subgroup picker,
next to the choice of a guarded wallet. The choice rides the ceremony proposal,
the parent's quorum signs it into the birth certificate as the delay on the
parent's guardian tag, and the child's quorum signs it again. An inherited
ancestor's leaf keeps the delay that ancestor chose, so each group's terms
follow its key down the tree.**

Read [group-wallet-guardians.md](./group-wallet-guardians.md) and
[subgroup-wallet-policy.md](./subgroup-wallet-policy.md) first. This builds on
the guardian tag from the first and the wallet-policy plumbing from the second,
and it changes one section of the first: [*Why 12, and why for
everybody*](./group-wallet-guardians.md#the-leaf).

**Built**, phases 1-8, one commit each, in the order the plan asked for —
Phase 7 before Phase 5 — written against the code as it stood at `60cb3fa7`,
schema v29; the build is schema v31. `GuardianDerivation.DELAY_RANGE`,
`grant(..., delay)`, `BlockDuration` and `delayText` are the entry points. The
phases below are kept as written, line references included, which have since
moved; where the build chose differently the table says so.

| what the plan said | what it turned out to be |
|---|---|
| Phase 2: one jvmTest | two. The script edges are in `GuardianSpendRoundTest` (commonTest), beside the 12-block test they generalise; the builder and the check are in `GuardianSigningJvmTest`, where the funded-state fixture is |
| Phase 3: `BlockDuration` in `wallet/` | in `text/`, beside `Sats.kt`, with `groupedBlocks()`. `delayText` and `approximateDuration` are in `ui/composable/widgets/DelayText.kt`. `BlockDuration.describe` says the same in fixed English for `ProposedEvent`, which is not a composable |
| Phase 4: a migration test | none, as for the policy's v29: the nullable auto-migrations have none, and every jvmTest's in-memory database runs at v30 |
| Phase 5: `setGuardianDelay` stores what was typed | two calls: `setGuardianDelay(blocks)` for a preset and `setGuardianDelayInput(text)` for the field, which drops anything but digits and stops at seven, enough to say *at most 65,535* about a number past it. The presets are `SelectSubgroupAdminsViewModel.DELAY_PRESETS`, as `FilterChip`s in a `FlowRow`. A retry comes back on its preset when it was one, and in the field when it was not |
| Phase 5: tests of `confirm` | not written, as for the policy: `confirm` needs an `Identity` with a private key and settled key packages |
| Phase 6: the summary's own-leaf line | a separate element, *this group after 144 blocks, about 1 day*, after the existing reach line |
| Phase 7: the guarded section's title says the shortest delay | not built. Each guardian is listed nearest first with its own delay, named by the room this device holds for it — `GroupWalletUIState.guardianNames` — and otherwise *a group above this one* |
| Phase 7: `withinReach` reads `wallet.account.guardians[position].delay` | `GuardianManager.GuardedWallet.delay`, the same thing named once |
| Phase 4: schema 29 → 30 | **30 → 31.** The default branch took 30 for the member-wallet tables (`docs/member-guarded-wallets.md`) while this was being built, so `DkgSession.guardianDelay` is v31 |
| nothing about member wallets or funding | both landed on the default branch meanwhile, quoting 12. On merging: `GuardedWalletRow.arriving` counts by this group's own leaf like `withinReach`; the funding form says that leaf's delay; a member wallet's screens and its proposal summary say each guardian's delay, through `MemberWalletManager.delaysOf` -- the group's own leaf at the default, every group above at the delay it chose. A group could not then choose the delay on its own leaf in a member's wallet; since [member-wallet-delay.md](./member-wallet-delay.md) it does, in a policy it signs, and a group that signs none does not guard members' wallets at all |

Still to do by hand: the three-emulator run in Phase 8.

---

## What is already per leaf, and what is not

Less of this is new than it sounds. The guardians work wrote the delay into
every tag precisely so that a later delay would be "a later account's tags, not
a new format", and most of the code already reads it from there:

| piece | today |
|---|---|
| `GuardianTag` | `["guardian", <room>, <key>, <chain code>, <delay>]`. `parse` already accepts 1 to 65,535 |
| `GuardianDerivation.Guardian.delay` | a field, defaulting to `ANCESTOR_DELAY` |
| `GuardianDerivation.leafScript` | already writes 1 to 16 as `OP_1`…`OP_16` and anything above as a minimal number push, and refuses anything outside 1 to 65,535 |
| `grantedTo` | `copy(...)` of the guardian, so an inherited key already keeps its delay one step down |
| `descriptorTree` | `older(${guardian.delay})` |
| `GroupSpend.check` | reads the spender's own leaf's delay and requires every input's sequence to equal it |
| `GroupSpendBuilder.lockOf` | per leaf: offers a guardian only coins at least that deep, and puts that sequence on every input |
| fee sizing, `GroupSpend.kt:390` | built from the real leaf script, so a longer number push is already counted |
| `GuardianManager.takenIn` | rebuilds each leaf from its own delay |

What still assumes 12:

| piece | where |
|---|---|
| `grant()` | the parent's own new entry takes the default, `ANCESTOR_DELAY` |
| `refuseCertificate` | compares the proposal to `grant()`, so any own delay other than 12 is refused |
| `GuardedWalletRow.withinReach` | `confirmations >= ANCESTOR_DELAY` (`GroupWalletUIState.kt:89`) |
| every string that quotes a delay | the picker (`SelectSubgroupAdminsScreen.kt:395`), the join gate (`DkgJoinApprovalScreen.kt:78`), the wallet screen (`GroupWalletScreen.kt:386`, `:408`, `:461`, `:672`), and six strings in `strings.xml` that say `%1$d confirmations` as if there were one number |
| the guardians note | *Why 12, and why for everybody* |

So the work is three things: let the parent choose, carry the choice to
everybody who signs it, and stop every screen from saying "12".

---

## The limits

### The floor: 6 blocks

About an hour. It keeps the property the guardians note gives as the reason for
a delay at all: **the child's own spends always go first.** A guardian cannot
race the child for a payment that has only just arrived, nor take the change
from a spend the child made a few minutes ago. Below about six blocks, one block
found quickly or a short reorg can put a fresh coin within a guardian's reach
before the child has had a chance to act on it.

### The ceiling: 65,535 blocks

BIP68 gives a relative lock 16 bits of `nSequence`. With the type flag (bit 22)
clear, the value counts blocks: 1 to 65,535, which is about 455 days, or a
little over 15 months. That is the most the protocol can express **for a lock
that counts from the coin's own confirmation**, which is the kind the guardian
leaf has to be.

Two other locks look longer and are not the same thing:

- **BIP68 in time units** (bit 22 set) counts 512-second steps, also 16 bits.
  Its ceiling is 65,535 × 512 s, about 388 days, so it is *shorter* than the
  block form. It also measures median time past rather than blocks. Not used.
- **An absolute lock** (`OP_CHECKLOCKTIMEVERIFY`) can name any height or date,
  but it counts from a fixed point rather than from the coin. A leaf with one
  would become spendable by the guardian for every coin at once, on one day,
  however recently each arrived. That is a different promise, and it is listed
  under [what this does not do](#what-this-does-not-do).

Staying at or below 65,535 also keeps two other things true without extra
checks:

- **bit 22 is never set**, so the number is always read as blocks;
- **the sequence stays far below `GroupSpend.MAX_SEQUENCE`** (`0xfffffffd`), so
  a guardian spend still signals replaceability and *Bump the fee* still works.

### The encoding, at its edges

Script numbers are little-endian, sign-and-magnitude, minimally encoded.
`Script.encodeNumber` in bitcoin-kmp already does this, including the zero byte
a positive number needs when its top byte has the high bit set. The cases a test
has to pin, because each is where the encoding changes length:

| delay | `<delay>` in the leaf | leaf script |
|---|---|---|
| 6 | `OP_6` | 36 bytes |
| 16 | `OP_16` | 36 bytes |
| 17 | `0x01 0x11` | 37 bytes |
| 127 | `0x01 0x7f` | 37 bytes |
| 128 | `0x02 0x80 0x00` | 38 bytes |
| 32,767 | `0x02 0xff 0x7f` | 38 bytes |
| 32,768 | `0x03 0x00 0x80 0x00` | 39 bytes |
| 65,535 | `0x03 0xff 0xff 0x00` | 39 bytes |

A guardian input therefore costs at most three weight units more than it does
today. That is under one vbyte, and it is paid only when a guardian spends.

---

## What a longer delay changes, said plainly

The guardians note is candid that two hours is not autonomy: "two hours is not
long enough for a child to notice a coin coming within reach and move it."
That stops being true as the delay grows, and the picker has to say so.

**The clock is per coin, and spending resets it.** A coin moved by the child is
a new coin, confirmed afresh. So a child that spends, or merely moves, each coin
before it is `d` blocks old keeps every coin out of every guardian's reach for
as long as it keeps doing that. With `d = 12` that would mean a transaction
every two hours, which is not practical. With `d = 4,320`, about a month, it
means one consolidation a month, which is cheap.

So the choice is a real trade, and the picker's description says both halves:

| delay | the guardian is | the child is |
|---|---|---|
| short (hours) | an override: in practice it can take any coin the child has not just received | under the guardian's control |
| long (months) | a backstop: it reaches coins the child has left alone for that long, such as a dormant wallet or a group that has stopped meeting | able to keep its coins out of reach by moving them before the lock runs out |

A long delay is the right choice for a parent that wants a recovery path for a
subgroup that falls silent, and the wrong one for a parent that wants to be able
to take funds back from a subgroup that is still active.

---

## Phase 0 — decisions, before any code

Six, each with a recommendation except D1, which is decided. The phases below
assume the recommended answer.

### D1. Whose delay does the parent choose?

**Decided: its own leaf's only. An inherited key keeps the delay its ancestor
chose.**

With root → middle `M` → grandchild `C`:

```
cert(M), signed by root:  [ root/i(M)  delay d₁ ]                     root chose d₁
cert(C), signed by M:     [ M/i(C)     delay d₂,                      M chooses d₂
                            root/i(M)/i(C)  delay d₁ ]                passed down unchanged
```

`grantedTo` already copies the delay, so this is what the code does today, with
every `d` equal to 12. It keeps each delay the decision of the group whose leaf
it is: the root decided how long a coin in `M`'s subtree waits for the root, and
`M` cannot shorten or lengthen that. Under the wallet-policy plan's D4 a guarded
group can only make guarded subgroups, so the root's terms reach every guarded
descendant of `M` unchanged.

The alternative is one delay per certificate, chosen by the parent, applying to
every leaf. It is simpler to show, since a wallet then has one number. But it
lets `M` rewrite the root's terms in `C`'s wallet. A short `d₂` would bring the
root's reach closer than the root chose for `M`, and a long `d₂` would push it
further away. Neither was the root's decision. Rejected.

### D2. The range

**Recommended: 6 to 65,535, as `GuardianDerivation.MIN_DELAY` and `MAX_DELAY`,
enforced by the parser as well as the picker.** `GuardianTag.parse` and
`leafScript` move from `1..0xFFFF` to `MIN_DELAY..MAX_DELAY`. Every guardian tag
in existence says 12, so tightening the parser breaks nothing that was ever
written. A tag under 6 from a modified client then fails to read, and the
existing rule handles it: the certificate's guardian list reads as null, so the
wallet-policy read is null and `GroupKeyStateManager.parentageOf` drops the key
state.

### D3. The default

**Recommended: 12, unchanged.** It is what every existing guarded wallet uses,
and the guardians note gives the reasons for it. `ANCESTOR_DELAY` is renamed
`DEFAULT_DELAY`, because it is no longer the delay, only the one offered first.

### D4. How the picker asks

**Recommended: presets plus a custom number of blocks.**

| preset | blocks | shown as |
|---|---|---|
| 1 hour | 6 | about 1 hour |
| 2 hours (default) | 12 | about 2 hours |
| 1 day | 144 | about 1 day |
| 1 week | 1,008 | about 7 days |
| 1 month | 4,320 | about 1 month |
| 6 months | 26,280 | about 6 months |
| longest | 65,535 | about 15 months |
| custom | 6–65,535 | the field's supporting text, live |

A slider over 6 to 65,535 is unusable at the short end, where most choices are,
and a logarithmic one shows the user numbers they did not pick. Presets cover
what people mean. The custom field is for the exact number, and says what it
works out to while it is being typed.

### D5. How a delay is said in time

**Recommended: "about", the largest unit that makes the number at least 2, and
exactly one of a unit when the delay is within 5% of it.** The rule, at 10
minutes per block, with a month of 30.44 days and a year of 365.25:

1. If the delay is within ±5% of exactly one hour, day, month or year, use that
   unit, singular: *about 1 day*.
2. Otherwise use the largest of years, months, days, hours and minutes in which
   the delay comes to at least 2, rounded to the nearest whole: *about 36 hours*,
   *about 3 months*.

Worked through:

| blocks | exact | shown |
|---|---|---|
| 6 | 60 min | about 1 hour |
| 12 | 2 h | about 2 hours |
| 100 | 16.7 h | about 17 hours |
| 144 | 1 day | about 1 day |
| 216 | 1.5 days | about 36 hours |
| 1,008 | 7 days | about 7 days |
| 4,320 | 30 days | about 1 month |
| 13,140 | 91 days | about 3 months |
| 26,280 | 182.5 days | about 6 months |
| 52,560 | 365 days | about 1 year |
| 65,535 | 455 days | about 15 months |

Years appear only near exactly one year, because the ceiling is 1.25 years, and
"about 15 months" says more than "about 1 year". Weeks are left out: the units
asked for were minutes, hours, days, months and years. Adding weeks later is one
more row in the rule, for example "about 2 weeks" instead of "about 14 days".

**Always beside the block count, never instead of it.** Blocks are what the
script enforces. Ten minutes is the average, not a promise, so a delay of 144
blocks can take 20 hours or 30. The text reads *144 blocks, about 1 day*.

**Regtest shows blocks only.** Its blocks arrive when somebody mines them, so a
time estimate there would be wrong. Signet and the testnets use the same
10-minute target as mainnet and get the estimate. The picker always uses
mainnet's estimate, because a certificate is not for any one network.

### D6. Fixed at birth

**Recommended: yes, like the wallet policy.** The delay is part of the guardian
tag, the guardian tag is part of the certificate the child's key state carries,
and the guardians note's rule 2 fixes an account's guardian set, delays
included, at its first statement. Changing a delay means a new subgroup.

---

## Phase 1 — the range, and the parent's own delay, pure

In `GuardianDerivation`:

```kotlin
const val MIN_DELAY: Long = 6L
/** BIP68's block-based relative lock is the low 16 bits of nSequence. */
const val MAX_DELAY: Long = 0xFFFFL
const val DEFAULT_DELAY: Long = 12L         // was ANCESTOR_DELAY
val DELAY_RANGE: LongRange = MIN_DELAY..MAX_DELAY

fun grant(
    thresholdPublicKey: HexKey,
    ownChatRoomId: String,
    inherited: List<Guardian>,
    childChatRoomId: String,
    delay: Long = DEFAULT_DELAY,             // the parent's own leaf, D1
): List<Guardian>
```

- `grant` requires `delay in DELAY_RANGE` and puts it on the first entry only.
  The inherited entries go through `grantedTo`, unchanged, and keep theirs.
- `leafScript` requires `DELAY_RANGE` instead of `1..0xFFFF`.
- `GuardianTag.parse` reads only delays in `DELAY_RANGE` (D2).
- `Guardian.delay` keeps its default, renamed to `DEFAULT_DELAY`, so no caller
  that builds a guardian by hand changes.
- The class comment and the KDoc on the old constant are rewritten. "One value
  for every ancestor at every distance" stops being true.

**Tests**, `GuardianDerivationTest`:
- the encoding table above, byte for byte, through `Script.write(leafScript(...))`;
- `grant(..., delay = 4_320)` puts 4,320 on the parent's entry and leaves an
  inherited entry at the delay it arrived with;
- `descriptorTree` writes `older(65535)` and passes bitcoin-kmp's descriptor
  checksum;
- 5 and 65,536 are refused by `grant`, `leafScript` and `GuardianTag.parse`.

One commit. Nothing chooses a delay yet.

---

## Phase 2 — the script enforces it, at every edge

A jvmTest in the shape of `GuardianSpendRoundTest`. For each delay in `[6, 16,
17, 127, 128, 32_767, 32_768, 65_535]`, a parent quorum's shares sign a
script-path spend of a child's coin. Then:

- `Transaction.correctlySpends` accepts the spend at `nSequence = delay` and
  refuses it at `delay - 1`;
- `GroupSpend.check` refuses an input whose sequence is not the leaf's delay;
- `GroupSpendBuilder.spendable` offers a coin with `delay - 1` confirmations to
  nobody but the owner, and offers it to the guardian at `delay`.

This is the phase that would catch an encoding mistake before a coin could. A
leaf whose lock is pushed non-minimally is a coin nobody can spend through that
leaf.

---

## Phase 3 — saying a delay in time

New, pure, in `wallet/`:

```kotlin
object BlockDuration {
    const val MINUTES_PER_BLOCK = 10

    enum class Unit { MINUTES, HOURS, DAYS, MONTHS, YEARS }
    data class Approximate(val count: Int, val unit: Unit)

    /** D5's rule. */
    fun approximate(blocks: Long): Approximate
}
```

And one composable beside the other text helpers:

```kotlin
@Composable
fun delayText(blocks: Long, chain: Chain?): String   // "144 blocks, about 1 day"; regtest: "144 blocks"
```

The units are **plurals**, the catalogue's first: `<plurals
name="about_n_hours">` and the rest, with `one` and `other`, read through
`pluralStringResource`. A plain `%1$d hours` string reads *about 1 hours*, and
the sentence-case audit cannot catch that. `StringCatalogueJvmTest` gains a
case asserting that plurals resolve on the JVM, as CLAUDE.md asks.

**Tests**, `BlockDurationTest`: every row of D5's table, both edges of each ±5%
window (for example 137 and 151 blocks for *one day*), and the full range from 6
to 65,535 never returning a count of 0 or a unit above years.

---

## Phase 4 — the ceremony carries the delay

The same path the wallet policy took in the policy plan's Phase 2, one field
further:

- `DkgRitualEvents`: a `["guardian_delay", "<blocks>"]` tag on a subgroup's
  ceremony proposal, written only beside `["wallet", "guarded"]`;
- `ChillDkgRitualManager.proposeRitual(guardianDelay: Long?)`. It requires null
  unless the policy is `GUARDED`, and a delay in `DELAY_RANGE` when it is set,
  checked before the running-ceremony lookup as the policy is;
- `acceptProposal` files it, and drops a proposal whose delay does not read,
  falls outside the range, or comes without a guarded policy;
- **Schema 29 → 30**: the nullable `DkgSession.guardianDelay`. Null for every
  ceremony before this and for every unguarded one, an auto-migration like the
  policy's.

A guarded ceremony from a build that has the policy and not the delay carries no
delay. It is certified at `DEFAULT_DELAY`, which is what that build would have
written.

**Tests**, in `SubgroupCeremonyInParentRoomTest`: the delay travels on the
proposal and nowhere else, and lands on every admin's session; a delay with an
unguarded policy, a delay of 5, and `"twelve"` are each dropped; a guarded
proposal with no delay files null.

---

## Phase 5 — the picker, and the join gate

### `SelectSubgroupAdminsScreen`

Under the *Guarded wallet* row, shown only while it is selected, a *How long
before the groups above can spend a coin* section:

- D4's presets as a row of `FilterChip`s, wrapping, the default selected;
- *Custom* opens an `OutlinedTextField` with a number keyboard. Its supporting
  text is the live `delayText` while the value is in range, and the reason
  (*at least 6 blocks*, *at most 65,535 blocks*) while it is not;
- under both, one line of D1's consequence for the parent's own leaf, and
  [the trade above](#what-a-longer-delay-changes-said-plainly) in a sentence:
  *A coin the subgroup moves starts waiting again, so a long delay lets it keep
  its coins out of reach by moving them.*

The description on the guarded row stops quoting one number. It lists the
parent's own delay, plus *and the N groups above it, on the terms they set*
when the parent's own certificate carries guardians. The picker reads those
terms from `ChatRepository.guardiansOf`, which it already calls.

In `SelectSubgroupAdminsViewModel`:

- `guardianDelay: MutableState<Long>`, starting at `DEFAULT_DELAY`;
- `setGuardianDelay` clamps nothing and stores what was typed, because the field
  has to be able to show *at most 65,535* about the number the user actually
  entered;
- `refusal()` refuses an out-of-range delay under a guarded policy, before the
  key-package wait, as the policy refusals are;
- `prefillFromRetry` restores the delay from the failed ceremony;
- `confirm` passes it, and passes null for an unguarded subgroup.

CLAUDE.md applies in full: chips and the field from `material3`, spacing from
`MaterialTheme.spacing`, strings and plurals in the catalogue in sentence case,
and the custom field with a real label and supporting text, not a placeholder.

### `DkgJoinApprovalScreen`

The guarded line gains the delay: *The subgroup's wallet will be guarded: this
group can spend a coin in it once it has 144 confirmations, about 1 day.* An
inherited ancestor's terms are not on this screen, because a member deciding
whether to join cannot check them. They are on the wallet screen, from the
verified certificate, in Phase 7.

**Tests**, `SelectSubgroupAdminsViewModelJvmTest`: the default is 12; a preset
and a typed delay are what `refusal()` judges; 5 and 65,536 are refused with
their reasons; a retry restores the delay; an unguarded subgroup confirms with
no delay.

---

## Phase 6 — the certificate carries it, and both quorums check it

### Proposing

`SubgroupManager.proposeBirthCertificate` reads `key.guardianDelay` off the
child's ceremony, as it reads the policy, and passes it to `grant`:

| ceremony | certificate's own entry |
|---|---|
| guarded, delay `d` | `d` |
| guarded, no delay (older build) | `DEFAULT_DELAY` |
| unguarded | no guardian tags at all, as today |

### Every parent signer

`refuseCertificate` stops comparing the whole proposal to `grant()` with the
default. Instead:

- the first entry's delay is in `DELAY_RANGE`, which reading the tag already
  guarantees under D2;
- the proposal equals `grant(..., delay = proposed.first().delay)`. The key, the
  chain code and the order are checked exactly as before. The **inherited**
  entries' delays must be exactly the ones on the group's own certificate,
  because `grant` copies them, so a proposer cannot shorten the root's wait in
  the grandchild's wallet (D1).

The existing test case *a shorter delay*, `copy(delay = 1)`, keeps refusing,
now because 1 is out of range. Two cases are added:
- an inherited entry with its delay changed is refused;
- an own delay of 4,320 is accepted.

### What the parent's admins read

`ProposedEvent`'s certificate summary says the delay on the parent's own leaf:
*this group can spend from its wallet once a coin has 144 confirmations, about
1 day*. `ProposedEvent` is not a composable, so it uses
`BlockDuration.approximate` with fixed English words, as the rest of that file
does.

### Every child signer

`SubgroupManager.refusePolicy`, behind `refuseKeyState`, also requires the
certificate's first guardian delay to equal the ceremony's announced
`guardianDelay`, when there is one. This is the same consent rule as the policy:
the admins were shown the number at the join gate, and joining was agreeing to
it.

**Tests**, in `SubgroupManagerJvmTest`: each row of the proposing table;
`refuseCertificate`'s new cases; a key state whose certificate carries 12 under
a ceremony that announced 4,320 is refused by `refuseKeyState`.

---

## Phase 7 — every screen says each leaf's own delay

Once a wallet can hold leaves with different delays, no sentence with one
`%1$d` in it is true any more.

**The child's wallet screen** (`GroupWalletScreen`, `OpenWalletAction` and the
guarded wallet's header). The sentence that quotes one number becomes a list
under it, nearest ancestor first, one row per guardian:

```
Guarded
  #Council          after 144 blocks, about 1 day
  #Ekklesia         after 4,320 blocks, about 1 month
```

The names come from the rooms the device has, falling back to *a group above
this one*. The shortest delay is the one that matters most to the child, so the
section title says it: *The groups above can spend a coin from 144
confirmations, about 1 day*.

**An ancestor's *Guarded wallets* list** (`:672`). `GuardedWalletRow.withinReach`
uses this group's own leaf's delay, `wallet.account.guardians[position].delay`,
instead of the constant. Each row's supporting line says that delay, because
two of a group's descendants can have made it different promises.

**The detail screens.** The subgroup rows that say *Guarded wallet* since the
policy plan's Phase 8 become *Guarded wallet, 144 blocks, about 1 day*, read off
the certificate's first guardian.

**The send screen**, for a guardian spending from a descendant. Its
"spendable" line already counts only coins past this leaf's lock, through
`GroupSpendBuilder.lockOf`. It gains the delay in words, so a guardian looking
at a balance of zero learns why.

The six strings that say `%1$d confirmations` are replaced, not edited: each
takes the `delayText` as a `%1$s`, or is split into a title and the per-guardian
rows.

**Tests**: `GroupWalletViewModelJvmTest` and a `GuardedWalletRow` test with two
guardians at 12 and 4,320, where a coin with 100 confirmations is within the
first's reach and not the second's.

---

## Phase 8 — docs, and checking it on the emulators

- [group-wallet-guardians.md](./group-wallet-guardians.md): *Why 12, and why for
  everybody* becomes *Why a delay, and who sets it*. It keeps the reasoning for
  a floor, adds the autonomy trade, and links here. The headline's `12` becomes
  `<d>`.
- [subgroup-wallet-policy.md](./subgroup-wallet-policy.md): the guarded row on
  the picker gains the delay, one sentence.
- [README.md](./README.md): this note's row and a sentence on reading order.
- This note: *Built*, and the table of where the build chose differently.

By hand, regtest, three emulators:

1. The root makes a guarded subgroup `M` at 6 blocks, and `M` makes a guarded
   subgroup `C` at 20 blocks.
2. `C`'s wallet screen lists `M` at 20 blocks and the root at 6.
3. With a coin in `C` mined 10 blocks deep, the root can spend it and `M`
   cannot. At 20 blocks deep, both can.
4. A guardian spend from the root goes through at `nSequence = 6`, and a
   hand-built one at 5 is rejected by the regtest node as non-final.

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
| 1 | `GuardianDerivation`, `GuardianTag` | dark: the default is still 12 everywhere |
| 2 | tests only | — |
| 3 | `BlockDuration`, the first plurals | dark: nothing shows it yet |
| 4 | DKG proposal tags, `DkgSession`, schema 30 | dark: nothing sets a delay |
| 5 | picker, join gate | **the switch** |
| 6 | certificate, both signers' checks | with 5 |
| 7 | wallet, detail and send screens | before 5 |
| 8 | docs, emulator run | last |

**Phase 7 should land before Phase 5.** A build that can make a wallet with a
6-block leaf, while its own wallet screen says 12, would be telling a group the
wrong number about its own money. Phases 1-4 and 7 change nothing a user can
choose, so they can go out first. Phases 5 and 6 go together, because a picker
whose number the certificate did not carry would be a number that meant
nothing.

**Mixed versions.** A parent signer on a build from before Phase 6 compares the
proposal to `grant()` with 12, so it **refuses** any other delay. That is the
safe failure: it never signs a delay it did not check. A group whose signers
are not all updated can still make subgroups at 12, but not at any other delay.
Readers on older builds already build each leaf from its own tag, so they derive
the right addresses and enforce the right sequence. They only show the wrong
number on screen.

---

## What this does not do

**It does not let the parent change a delay after birth** (D6). A different
delay is a different subgroup.

**It does not let a middle group change an ancestor's delay** (D1). The root's
terms reach every guarded descendant as the root set them.

**It does not give an ancestor two delays in one wallet.** One leaf per ancestor,
as before. "This group can take any coin after a week, and everything after a
year" would be two leaves per ancestor and a different tree.

**It does not offer an absolute lock.** "On 1 January 2027, every coin in this
wallet becomes the root's" is a `OP_CHECKLOCKTIMEVERIFY` leaf. It is not a delay
counted from each coin, and it is a different promise about time, with a
different ceiling and different consequences for a child that keeps moving its
coins.

**It does not make a long delay safe against an active child.** A child that
moves its coins before the lock runs out keeps them out of reach, and the
picker says so. Nothing on mainnet can force coins to stay put without a
covenant, which the guardians note has already said there is none to write.

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| one delay per certificate, for every leaf | lets a middle group rewrite its ancestors' terms (D1) |
| time-based BIP68 locks | a lower ceiling (about 388 days), coarser 512-second steps, and median time past rather than blocks, for no gain |
| a slider over 6 to 65,535 | unusable at the short end, where most choices are |
| a floor of 1 | lets a guardian race the child for a payment one block after it arrives, the thing a delay exists to prevent |
| showing time without blocks | blocks are what the script counts; ten minutes is an average |
| a time estimate on regtest | its blocks come when somebody mines one |
