# Sending an amount from the group's wallet

*Send from the group's wallet* today sends everything the group has confirmed, to
one address, less the fee. This note plans the other half: a member types an
amount in sats, the rest comes back to the group as change, and the other members
can see that the change is the group's before they sign.

Read [group-chain-wallet.md](./group-chain-wallet.md) first, Phases 8 and 10 in
particular. This plan is that note's third rollout gate, and it starts from the
code as it stands at `96055da5`.

**Phase 1 built; Phases 2-7 not.** Written against `GroupSendScreen`, `GroupSendViewModel`,
`GroupSpendBuilder`, `GroupSpend` and `GroupWalletRollout` as they are at that
commit.

---

## What is already there

Most of this feature has been built and switched off. The plan is mostly about
why it is off and what has to be true before it can be switched on, not about a
new screen.

| piece | where | state |
|---|---|---|
| an amount, largest coin first, change to the next unused change address, dust change goes to the fee | `GroupSpendBuilder.send` | built, tested in `GroupSpendBuilderTest` |
| change counts as change only if its claimed derivation reproduces its script | `GroupSpend.check`, check 5 | built, runs on every member's device before signing |
| the approval screen shows every output, with change labelled as change | `FrostSigningScreen.WhatIsBeingSpent` | built |
| *Send everything* switch, *Amount in sats* field | `GroupSendScreen`, `GroupSendViewModel.amountField` / `sendEverything` | built, behind the gate |
| the gate | `GroupWalletRollout.SEND_AN_AMOUNT = false` | closed |
| the preview says how much change comes back | `SendPreview` | built |

The gate's own kdoc gives the lift condition: *"send-an-amount has had a release
with nobody reporting a mislabelled change output."* While the gate is a single
`const` that is false on every network, nobody can send an amount, so nobody can
report anything. **The condition cannot be met as the gate is written.** Phase 0
settles it by opening the gate on every network at once, and Phase 2 removes it.

Reading the gated code turned up five things to fix before the field is shown
to anyone:

1. **A decimal is silently multiplied.** `preview()` reads the amount with
   `filter { it.isDigit() }`. Someone who types `21000.5` gets 210,005 sats, and
   someone who types a bitcoin amount, `0.0005`, gets 5 sats. The first is a
   tenfold overpayment that the preview shows only as a number.
2. **An amount too long for a `Long` disappears.** `toLongOrNull()` returns null,
   `preview()` returns null, and the button stays disabled with no reason given.
3. **Two open spends with change pick the same change address.**
   `nextChangeIndex` is the first change address Electrum has not seen used, and
   a proposal waiting for signatures has not used it yet. Both proposals pay
   change to `…/1/0`. Nothing is lost, but the address is reused, and change is
   exactly the output whose job is to not be linked to anything.
4. **"Nothing to spend" is shown when the change branch has not loaded.**
   `send` returns `NothingToSpend` when `nextChangeIndex` is null. That happens
   before Electrum has answered for the change branch, while the group may hold
   plenty.
5. **Dust change becomes fee without a word.** Phase 8 of the wallet note said
   *"the remainder goes to fee, and the screen says so"*. The builder does it
   and the screen does not: the preview shows a fee a few hundred sats higher
   than the fee rate implies, and no reason.

---

## Phase 0: three decisions, before any code

**Half a day.** They change what Phases 2 and 4 build, so they come first.

1. **Where the gate opens first.** **Decided: on every network at once, relying
   on the mainnet cap.** `MAINNET_CAP` already holds any one spend proposed from
   this device to 100,000 sats, and that limit does not depend on the gate, so
   the most a mislabelled change output can put at risk on mainnet is one capped
   spend. Three things stand where the gate stood. The cap bounds the amount.
   Check 5 runs on every signer's device. And Phase 3's end-to-end test is the
   lab version of the report the gate was waiting for. The other option was
   test networks first and mainnet after a release, the way `capFor` treats
   networks. It was turned down because it delays mainnet by a release and buys
   less than it looks, since the cap already makes the first mainnet spends
   small.
2. **Whole sats only.** No bitcoin unit, no fiat, no decimal point. `Sats.kt`
   already gives the reason for the wallet: comparing two integers is easier
   than reading a decimal point eight places in. An input that contains a
   decimal separator is refused with a message, never rounded or stripped.
3. **"Send everything" stays a switch, not a *Max* button.** A *Max* button
   would fill the amount with `total − fee`. That value changes when the fee rate
   changes, and it gives the group change it does not want when a coin confirms
   between typing and pressing. The switch builds `sendAll`, which has no
   change at all. That is the one difference between the two paths, and it should
   stay visible in the form.

---

## Phase 1: the amount is sats, or says why not

**One day. Built.**

A parser and an output transformation in `text/Sats.kt`, next to `grouped()`,
so that reading and writing an amount are in one file:

```kotlin
sealed interface SatsInput {
    data object Empty : SatsInput
    data class Sats(val amount: Satoshi) : SatsInput
    data object NotWhole : SatsInput     // a decimal separator
    data object TooLarge : SatsInput     // more than 21,000,000 bitcoin
    data object Unreadable : SatsInput   // letters, or grouping that groups nothing
}

fun parseSats(text: CharSequence): SatsInput
val GroupedSats: OutputTransformation
```

- **Grouping separators are allowed and dropped:** `,`, space, no-break space,
  narrow no-break space, `'`, `_`. People paste `40,000` and `40 000`, and the
  second is how a South African locale groups thousands.
- **`.` is refused, and so is `,` followed by one or two final digits**
  (`40,5`, `40,50`), because in that locale those are decimals. `40,000` is
  grouping.
- **Grouping has to group.** The first group has one to three digits and every
  later group exactly three, or the input is `Unreadable`: `40,0000`, `4 0`,
  `1234,567`. Guessing is how the bug above got in, and a reason that is not
  about decimals deserved its own case rather than being filed under
  `NotWhole`.
- **ASCII digits only.** Kotlin's `isDigit` accepts the digits of every script,
  and so does the JVM's `toLong`.
- **At most `MAX_SATS`**, 2,100,000,000,000,000. Significant digits are counted
  before `toLong()`, so a forty-digit paste is `TooLarge`, not an overflow.

**On the field, display only.** The plan said an `inputTransformation` would
store only digits. Built that way, a pasted `40,5` would have its comma
stripped and become 405, which is the same bug moved to the keyboard. So the
field holds exactly what was typed, `parseSats` is the only thing that reads
it, and `GroupedSats`, an `outputTransformation`, shows the text grouped as
`grouped()` writes it, but only when the text is plain digits. Anything else
is shown as it was typed, because regrouping `40,5` would show a number it is
not.

`GroupSendViewModel.amount()` is `parseSats(amountField.text)`, and `preview()`
returns `GroupSpendBuilder.Result.InvalidAmount(input)` for anything that is
neither `Sats` nor `Empty`. It does this before it checks for an address or a
fee rate, because a wrong amount is not an incomplete form. Until Phase 4
moves amount errors onto the field, `SendPreview` shows the three reasons in
the preview line.

**Tests**: `commonTest/…/text/SatsTest.kt` covers every separator, the decimal
cases, the unreadable ones, empty versus zero, `MAX_SATS` and its overflow,
and what `GroupedSats` shows. In `GroupSendViewModelJvmTest`, typing
`21000.5` reads as `NotWhole` and never builds a spend of 210,005.

---

## Phase 2: the gate comes out

**Half a day.** Phase 0 opened the gate on every network, so what is left is
removing it, not turning it into a per-network switch. A `const` that is
always true is a branch the compiler keeps and a reader still has to follow.

- **`GroupWalletRollout.SEND_AN_AMOUNT` is deleted**, and its kdoc goes with it.
  `ENABLED` and `MAINNET_CAP` stay. The object's own kdoc keeps its rule, that a
  gate decides what this device offers, never what a quorum may decide, and
  now names two gates.
- **Its three reads go.** In `GroupSendScreen`, the *Send everything* row is
  always drawn and the amount field depends only on the switch. In
  `GroupSendViewModel.preview()`, the condition becomes `sendEverything.value`
  alone.
- **The send-all sentence goes.** `for_now_a_spend_sends_everything` has no
  reader once the `else` branch is gone. Remove it from `strings.xml`, so the
  catalogue holds no text that the app never shows.
- **`GroupSendViewModelJvmTest` stops branching.** `the preview waits for the
  form, and what it builds is what is proposed` asserts the amount-with-change
  case outright: 40,000 sats sent, change not null, and `GroupSpend.check`
  accepting the proposed PSBT. A test whose assertions depended on a constant
  was testing whichever branch was compiled in.
- **The mainnet cap is now what stands between a member and a large mistake
  sent as an amount.** Add a view model test on `Chain.Mainnet` where an amount
  over 100,000 sats previews as `OverCap`. `GroupSpendBuilderTest` already
  covers the cap for send-all. This test covers it for an amount, which is the
  path the cap now protects.
- **Order the commit after Phase 3.** With the gate gone, two spends with
  change from one group can be open at once, so change-address reservation
  needs to be in the same release. Phases 1 and 3 can land behind the closed
  gate in any order. This phase is the one that makes them reachable.

The wallet note's Phase 10 table loses its *send-all only* row, and its "built"
table's `SEND_AN_AMOUNT` row says the gate was removed, why, and which commit
removed it (Phase 6).

---

## Phase 3: change goes to an address the group can find

**One and a half days.** This fixes problems 3 and 4 above. It is the only phase
that touches the builder's choices, so it is the one that needs the most tests.

- **Change indices are reserved like coins.** Next to
  `FrostSigningManager.reservedOutPoints`, add `reservedChangeIndices(database,
  chatRoomId): Set<Long>`. It decodes the PSBT of every `GroupTransaction` whose
  session is not `FAILED`, and collects each output's change derivation. It
  covers the same sessions as `reservedOutPoints`, for the same reason. Expose
  it on `FrostSigningRepository`, and carry it on `GroupSendUIState.Ready` next
  to `reserved`.
- **`GroupWalletState.nextChangeIndex(reserved: Set<Long>)`** returns the first
  change index that is unused and not reserved. `GroupSpendBuilder.send` takes
  that set and calls this.
- **Inside the watched window, or refused.** `CHANGE_LOOKAHEAD` is 3, and its
  comment says *"change addresses are only ever chosen by a spend, one at a
  time"*, which reservation makes untrue. The change wallet watches three
  addresses past the first unused one. So a fifth open spend with change would
  pay an address that Electrum is not watching. That is not a loss, because the
  address derives, but the change would be missing from the balance until the
  window moves. Two changes:
  1. raise `CHANGE_LOOKAHEAD` to 10, the same as receive, since several members
     can now each hold an open spend with change;
  2. when the next free index is past the watched window, `send` returns a new
     `Result.TooManyOpen`. The screen says to wait for an open spend to
     settle, or to send everything.

  Check the window against `ElectrumMiniWallet`'s generator before trusting
  either number. The wallet note's comment says the look-ahead counts past the
  first *unused* address, and this phase depends on that being right.
- **The change branch not loaded yet is "still finding", not "nothing".** When
  the change `WalletState` has no addresses yet, `send` returns a new
  `Result.FindingChange`, and the form shows it with the same indicator as
  `walletState == null` (*Finding the group's coins*), not as an error.
- **Dust change shows up in the preview.** Add `absorbed: Satoshi` to
  `Result.Built`: what the build added to the fee because the change would have
  been dust. It is zero for `sendAll`, for a build with change, and for a bump.

**Tests**, extending `GroupSpendBuilderTest`:

- with change index 0 reserved, `send` pays change to index 1, and `GroupSpend.check`
  labels it change;
- reserving every index in the watched window gives `TooManyOpen`;
- an empty change branch gives `FindingChange`, not `NothingToSpend`;
- the dust case's `absorbed` plus the fee at the rate equals its fee.

And extending `GroupWalletSpendRoundTest` with **the end-to-end case the removed
gate was waiting for**: two devices, an amount with change, signed to `COMPLETE`.
Assert that the change output is labelled change on the device that did not
propose it, and that once the change coin is in the wallet state it counts in
`confirmedBalance`. That is the test version of "nobody reported a mislabelled
change output", and with no test-network release before mainnet, it is the
only version there is before the feature ships.

`reservedChangeIndices` gets its own jvmTest next to whatever tests
`reservedOutPoints`. A `FAILED` session releases its index, and a `COMPLETE`
one keeps it until Electrum reports the address used.

---

## Phase 4: the form

**Two days.** Everything the screen draws, under the CLAUDE.md rules.

Order, top to bottom:

1. **Address.** Unchanged.
2. **Send everything.** The row, not only the switch, toggles it:
   `Modifier.toggleable(role = Role.Switch)` on the `Row`, the `Switch` taking
   `onCheckedChange = null`, and `minimumInteractiveComponentSize()`. That is one
   48dp target with one name, rather than a small switch beside a label that
   does nothing when tapped.
3. **Amount in sats**, shown when the switch is off:
   - `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number,
     imeAction = ImeAction.Next)`, with Phase 1's transformations;
   - `suffix = { Text(stringResource(Res.string.sats)) }`, so the unit is part of
     the field and not only its label;
   - `supportingText` holds **how much can be spent**: *"120,000 sats confirmed
     and free to spend"*. That is the sum of what `GroupSpendBuilder.spendable`
     returns. The function is private today; make it public, so the screen
     and the builder count the same coins. It is less than the wallet screen's
     balance by whatever open spends hold, and the line says *free to spend*
     because of that difference. On mainnet, while the cap holds, a second line
     says *"At most 100,000 sats in one spend while the wallet is new."*
   - `isError` and `supportingText` show **amount errors at the field**:
     `NotWhole`, `TooLarge`, `NotEnough`, `BelowDust`, `OverCap`. Address and
     fee problems stay in the preview below, so each error appears next to the
     field it is about.
4. **Fee rate.** Unchanged.
5. **Preview.** As today, plus one line when `absorbed > 0`: *"%1$s sats left
   over are too few to come back as change, and go to the fee."* The other
   addition is `TooManyOpen`.
6. **Nothing leaves until a quorum signs** and the button. Unchanged.

When the switch is turned on and off again, the amount typed is kept. The field
is hidden, not cleared, and `amountField` already outlives the composition.

**Strings**, sentence case, format arguments, in `strings.xml`:
`sats`, `s_sats_confirmed_and_free_to_spend`,
`at_most_s_sats_in_one_spend_while_the_wallet_is_new`,
`sats_are_whole_numbers`, `that_is_more_sats_than_there_are`,
`s_sats_left_over_go_to_the_fee`, `too_many_spends_are_open_with_change`.
(`for_now_a_spend_sends_everything` was removed in Phase 2.) The existing `while_the_wallet_is_new_at_most_s_sats` may be reworded and reused
rather than duplicated.

**Budgets that must not move:** 0 spacing `.dp` literals, 0 hardcoded colours,
0 unguarded `.clickable`, 0 untriaged `contentDescription = null`. The amount
field adds no icon.

**Test**, `jvmTest/…/ui/composable/GroupSendScreenJvmTest.kt`, in the style of
`ProposeGroupEventScreenJvmTest`, with a `Ready` state on regtest:

- typing `40000` shows `40,000` in the field and *Sends 40,000 sats* in the
  preview, and the button is enabled;
- typing `0.5` shows the whole-numbers error on the field, and the button is
  disabled;
- tapping the *Send everything* row hides the field, and tapping it again
  brings the field back with `40,000`;
- on mainnet, the field is shown with the cap line under it, and `150000`
  shows the cap error on the field.

---

## Phase 5: what the other members see

**Half a day, mostly reading.** The approval side has been built since Phase 8.
This phase checks that each place a spend is described says the payment and
not the total.

| place | reads | expected with change |
|---|---|---|
| `FrostSigningScreen.WhatIsBeingSpent` | every output, `isChange` | payment row, change row labelled *back to the group as change*, fee |
| `ProposedSpend.summarize` | the one payment's amount | *Send 40,000 sats to bcrt1…*, not the inputs' total |
| `FrostSigningManager`'s completion and confirmation lines | `amountSent` | the payment only: `amountSent` already excludes change |
| `GroupWalletScreen`'s spend rows | check | the payment, not payment plus change |

Add one test per row whose behaviour is not already tested: build a spend with
change through `GroupSpendBuilder.send`, check it, and assert the words. The
`GroupWalletScreen` row is the one not yet read for this plan. If it shows
anything but `amountSent`, fixing it belongs in this phase.

---

## Phase 6: checked, and opened

**Half a day.**

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

Then by hand, on regtest, with two devices in a group with an opened wallet:

1. fund it with two coins;
2. send an amount that needs one coin, and check that the preview's change
   matches the approval screen's change on the other device;
3. while the first spend is open, send a second amount, and check that it
   picks the other coin and a different change address;
4. sign both, confirm both, and check that both change coins appear in the
   wallet's confirmed balance;
5. send an amount that leaves dust change, and check the *go to the fee* line.

Then update [group-chain-wallet.md](./group-chain-wallet.md): remove the Phase
10 table's *send-all only* row, and make the "built" table's `SEND_AN_AMOUNT`
row say the gate was removed and that the mainnet cap stands in for it. This
note is already in [README.md](./README.md).

---

## Phase 7: the first release

**No code.** Mainnet gets send-an-amount in the same release as every other
network, so the first real reports come from both at once. The 100,000 sat cap
stays in force. It lifts on its own condition, which is about aggregation and
confirmation, not change, and lifting it is a separate decision from this
plan.

What to watch, and what each would mean:

- **A change output shown as a payment on the approval screen.** Check 5
  refused to label it, so its claimed derivation did not reproduce its script.
  That is a builder bug. Members are shown a payment that is really the
  group's own money, and anyone reading the approval screen closely sees an
  output to an unfamiliar address and declines. Fix it in the builder. Nothing
  needs to be put back behind a gate.
- **Change that never shows in the balance.** The change address is outside
  the watched window, which Phase 3 was meant to prevent. The coins are not
  lost, because the address derives, but the window or the reservation is
  wrong.
- **The same change address in two confirmed spends.** Reservation missed a
  session.

If either of the first two turns out to be widespread, the response is the
cap, not a new gate. Lowering `MAINNET_CAP` bounds the harm at once without
taking away the send-everything path that already works.

---

## What this does not do

- **Units other than sats.** No bitcoin, no fiat, no toggle (Phase 0, decision 2).
- **Several recipients.** `ProposedSpend` already describes a spend to several
  addresses, so the approval side would cope. The form, the cap and coin
  selection would each need rethinking.
- **Choosing coins.** Largest first, as built. Coin control is a disclosure on
  a form that most members never open.
- **Subtracting the fee from the amount.** The amount is what arrives. A member
  who wants "everything but some" sends an amount.

| phase | what | days |
|---|---|---|
| 0 | decisions | ½ |
| 1 | `parseSats`, field transformations | 1 |
| 2 | the gate comes out, committed after Phase 3 | ½ |
| 3 | reserved change, window, `FindingChange`, `absorbed` | 1½ |
| 4 | the form | 2 |
| 5 | what the other members see | ½ |
| 6 | checks, regtest by hand, docs | ½ |
| 7 | watching the first release | — |
