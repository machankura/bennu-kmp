# Which group guards a member's wallet, as the groups above see it

An ancestor's *Guarded wallets* list shows `@alice` with no word about where her
wallet sits. Suppose Alice is in two groups of one tree and each guards a wallet
for her. The root then sees two rows that read the same, and nothing on the screen
says they came from different branches. This plan names the group that **directly**
guards each member wallet, which is the group that signed its certificate. It
gathers a person's wallets under that person, so an ancestor can see at a glance
that one member has three wallets guarded by two groups.

Read [member-guarded-wallets.md](./member-guarded-wallets.md) first, Phases 10
and 11 especially. This plan changes what those phases draw. It does not change
what they verify.

**Built**, phases 1-6, one commit each, written against `263a25a7`, schema v31;
no phase changed the schema. `GuardedWallet.directGuardianChatRoomId`,
`GuardianManager.foundingNameOf`, `GuardedWalletNoticeEvent.certificateIn`,
`byPerson` and `MemberWalletManager.byWallet` are the entry points. The phases
below are kept as written; where the build chose differently the table says so.

| what the plan said | what it turned out to be |
|---|---|
| D3, third source: 30331 notices told to *any* room here | only those told to the holding room, which `guardedWalletsOf` already reads. Reaching every room would have been a query by kind across rooms, the one new query the plan said it did not need |
| Phase 2: `foundingNameOf` | and `foundingCertificateOf` beside it, which returns the checked event: Phase 3 forwards the certificate, not the name |
| Phase 3, step 4: each told row paired with its own carried certificate, and the copy with one wins a fold | every certificate the room's notices carry is pooled, with the ones its 30331 statements embed, and offered to every wallet. Each is checked against the room and parent it would name, so one that certifies neither is never used, and the fold needs no tiebreak |
| Phase 3, not in the plan | *Tell the groups above* offers only guardians above the room it is shown from. It offered every guardian but that room, so a device in P and M was asked to tell M about M's own member's wallet -- found writing the two-hop test |
| Phase 5, step 2: `guarded_by_this_group`, `n_guarded_wallets` | noun phrases inside `guarded_by_s` -- `this_group`, `a_group_below_s`, `a_group_below_this_one` -- so the button descriptions can use the same phrase mid-sentence; `n_wallets`, since *2 guarded wallets, guarded by 2 groups* says it twice; and `s_comma_s` to join the two plurals |
| Phase 5, step 3: `guardedWalletName` gives `@alice · Savings` | every such sentence is *X's guarded wallet*, and *@alice · Savings's* does not read. A labelled wallet is *@alice's guarded wallet “Savings”*, an unlabelled one *@alice's wallet guarded by #M* |
| Phase 5, step 4: `displayName` becomes `@alice · Savings` | `displayName` is unchanged; `Spender.Guardian` gains `label`, and `ProposedSpend.guardedWallet` appends it the same way. The transcript's *The group signed …* line and the send form's title use it too |
| Phase 4: cards nearest first, then by guardian name | then by label as well, so two wallets under one group keep an order |
| Merged with [guarded-wallet-forwarding.md](./guarded-wallet-forwarding.md), which landed alongside | that note made forwarding automatic, so `GuardianManager.forward` -- on arrival and in the catch-up -- carries the certificate too, read by `guardianCertificateFor`. D5's rule lives in its `told`, which also counts a statement the room signed itself, and `alreadyTold` is gone. D5's extra transcript line never appears: that note's one line per wallet per group keeps the second notice silent. Both branches made the upward-only fix; its `upwardOf` is the one kept |
| Phase 6: `GuardedHereWallet(walletId, label, guardianName, holders)` | and `below`, the group it is known to sit below, for the line when no holder can name the guardian; with neither it is *another group*. `GuardedHere.guardianName`, which was the holder's room name, is now `holderName` |

Still to do by hand: Phase 7's run on signet.

---

## The example used throughout

```
R                       root
├── P                   R's guarded subgroup
│   └── M               P's guarded subgroup — guards Alice's "Savings"
└── N                   R's guarded subgroup — guards Alice's "Rent"
```

| wallet  | guardians, nearest first | R's `position` | R's `lineage`     |
|---------|--------------------------|----------------|-------------------|
| Savings | `[M, P, R]`              | 2              | `[P, M, W_savings]` |
| Rent    | `[N, R]`                 | 1              | `[N, W_rent]`       |

What R's wallet screen draws today:

```
Guarded wallets
  @alice · Savings      12,000 sats within this group's reach
  @alice · Rent          4,000 sats within this group's reach
```

And with no labels, two identical `@alice` rows. The *Fund* and *Spend* buttons
on both are described to a screen reader as *Fund @alice's guarded wallet*, so
nothing tells the two apart.

---

## What the code already knows

Most of the answer is already in memory, unused.

- **The direct guardian is `account.guardians.first()`.**
  `MemberWalletRequestEvent.verified` refuses a request whose nearest guardian is
  not the group it asks (`MemberWalletRequestEvent.kt:237`). `MemberWalletCertificateEvent.verify`
  also requires that same group to have signed the certificate. So
  `guardians[0].chatRoomId == certificate.pubKey == Verified.groupChatRoomId`,
  and an ancestor that has believed the certificate has believed this too.
- **The branch is `lineage.first()`.** `GuardianDerivation.lineage` gives the
  rooms from the ancestor's own child down to the wallet. Its first element is
  the child of *this* group that the wallet sits under. This group signed that
  child's birth certificate, so it can always name it.
- **The distance is `position`.** 0 means this group is the direct guardian. 1
  means a child is, and so on.
- **What the code cannot always do is put a name to `guardians[0]`.** Names come
  from `ChatRoom.subject`, and only for rooms this device is in
  (`GroupWalletViewModel.namesOf`, `guardedByGroupsHere`). Take a device in R but
  not in M. It has M's room id and nothing else.

Room ids are signing keys (`GroupKeyStateEvent.isSignedByRoom` checks
`pubKey == chatRoomId`). So any device can check M's birth certificate on its own.
It is a 30329 that P signed, and `SubgroupBirthCertificateEvent.certifies(cert, M, P)`
needs nothing but the event. The certificate is the thing an ancestor is missing,
and it does not have to trust whoever hands it over.

---

## Phase 0 — decisions, before any code

### D1. "Directly guarded by" means the certificate's signer

That is `guardians[0]`. It is not the room the notice was forwarded into, and not
the member who forwarded it. One wallet has exactly one direct guardian, however
many ancestors hold a leaf in it.

### D2. The founding name, then the live one, then where it sits

Order of preference for the direct guardian's name:

1. its **founding name**, from a 30329 this device has checked (D3);
2. its live `ChatRoom.subject`, where this device is in that room;
3. a phrase saying where it sits: *a group below #P*, using the branch, which is
   always known.

The founding name comes first because the same list already names subgroup rows
by founding name (`GuardedSubject.Subgroup.name`, "a rename does not reach it").
A card that said *#Translators* for the guardian of `@alice`'s wallet, two cards
below a subgroup row reading *#Translation* for the same room, would contradict
itself. It also means two members of R see the same words when they talk about
the row.

### D3. Where a founding name can come from

For a room `X` with parent `Y` (`guardians[i]` and `guardians[i + 1]`), every
source is checked with `certifies(cert, X, Y)`, never taken on trust:

| source | present on |
|---|---|
| the 30329 `Y` signed, in `groupSignedEventDao` under `Y` | every device in `Y` |
| `GroupKeyState.birthCertificateJson` for `X` | every device in `X` that holds its key state |
| a 30331 notice told to any room here, authored by `X`, which embeds `X`'s certificate | ancestors that were told of `X`'s own wallet |
| **the certificate carried on the 30333 notice itself** (Phase 3) | every device in every room the notice reached |

The first three already exist. On their own, they name M for R only when this
device is also in P or M, or was told of M's own wallet. The fourth source is the
reason for Phase 3.

### D4. The notice carries the direct guardian's certificate, as a tag

A 30333 carrying a 30334 gains one tag:

```
["certificate", <the 30329 that certifies guardians[0], whole>]
```

- **It is a tag, not a second content.** An older build reads `content` and
  nothing else, so it still believes the wallet and ignores the tag.
- **It is checked, not trusted.** A wrong certificate, or one that certifies a
  different room, is dropped, and the row falls back to D2's next step. It never
  stops the wallet itself from being believed, because the name is not what the
  leaf's signing depends on.
- **There is none for a root guardian.** If `guardians.size == 1`, no group is
  above to be told, so the case never arises.
- **A 30331 notice is unchanged.** The statement already embeds the subgroup's
  own certificate. Its direct guardian is its parent, which is not this plan's
  question (see *What this does not do*).

### D5. A wallet told without a certificate may be told again

`alreadyTold` matches on the statement's id. Notices sent before Phase 3 carry no
tag, and so as things stand they would block a better one forever. From Phase 3,
a room counts as told only if it holds a notice for that statement **with** a
certificate, or if this device has no certificate to add. The cost is one more
transcript line, *Shared @alice's guarded wallet with this group*, on rooms told
before. `guardedWalletsOf` already folds duplicates by `(id, chain, account)`.

### D6. An ancestor's list groups member wallets by person

The *Guarded wallets* section keeps subgroup rows as they are. Member wallets
move under a header for each person:

```
Guarded wallets
Wallets of groups below this one. …

  #Translation                            subgroup cards, unchanged
  ...

Members' wallets
  (avatar) @alice                          person header — opens her profile
           2 wallets, guarded by 2 groups
  ┌──────────────────────────────────────┐
  │ Savings                              │
  │ Guarded by #M, below #P              │
  │ 12,000 sats within this group's reach│
  │ This group's delay: …                │
  │                        Fund   Spend  │
  └──────────────────────────────────────┘
  ┌──────────────────────────────────────┐
  │ Rent                                 │
  │ Guarded by #N                        │
  │ …                                    │
  └──────────────────────────────────────┘
```

- **Every member gets a header, even with one wallet.** The layout keeps one
  shape, and the header's count is the summary the question asks for.
- **The header counts and the cards name.** *2 wallets, guarded by 2 groups* is
  two plurals. Writing the names into the header would need a list join, which
  Compose Resources does not localise. The names are one line below, on each
  card.
- **Cards are ordered by distance, then by guardian name.** Wallets from one
  branch sit together.
- **The card's guardian line by `position`:**
  - 0: *Guarded by this group*
  - 1: *Guarded by #N*
  - 2 and more: *Guarded by #M, below #P*, where `#P` is the branch.
- **The headline is the label. With no label, the guardian line moves up to the
  headline.** Two unlabelled wallets of Alice's are then still told apart.

### D7. The person's page shows one row per wallet, not one per holder

`guardedByGroupsHere` returns one `GuardedHere` per *holding* group. When P and R
are both here, Savings appears twice, and each row reads *Guarded by #P* or
*Guarded by #R*. That uses "guarded by" for the holder, which this plan reserves
for the direct guardian. Instead:

```
Wallets your groups guard
  Savings
  Guarded by #M
    #P: after 144 blocks, about 1 day          Spend
    #R: after 288 blocks, about 2 days         Spend
  Rent
  Guarded by #N
    #R: after 144 blocks, about 1 day          Spend
```

The holder lines reuse `guardian_after`, which the wallet screen already uses for
delays.

### D8. Rollout: no gate

All of this is display, apart from one backward-compatible tag. It needs no
`GroupWalletRollout` gate. Phase 3 is the only part another device reads, and an
older device ignores it.

---

## Phase 1 — the direct guardian, pure

`GuardianManager.GuardedWallet` gains three derived properties. They are not
fields, so no constructor or test fixture changes:

```kotlin
/** The group that signed the certificate: the wallet's nearest guardian. `docs/member-wallet-guarding-group.md`, D1. */
val directGuardianChatRoomId: String get() = account.guardians.first().chatRoomId

/** True when this group is the direct guardian. */
val isGuardedHere: Boolean get() = position == 0

/** This group's child the wallet sits under, or null when this group guards it directly. */
val branchChatRoomId: String? get() = lineage.takeIf { position > 0 }?.first()
```

The properties are defined for a subgroup's wallet too. Only a member's wallet
is drawn with them (D4, last point).

**Tests**, in `commonTest`, beside `GuardianDerivationTest`, building the
example's two accounts with `GuardianDerivation`:
- R's Savings has direct guardian M, branch P and position 2;
- R's Rent has direct guardian N, branch N and position 1;
- M's own copy of Savings is `isGuardedHere`, with a null branch.

---

## Phase 2 — naming from what is already here

1. **`GuardianManager.foundingNameOf(database, roomId, parentRoomId, carried: Event? = null): String?`**
   tries D3's sources in order. These are `carried`, then the 30329s
   `groupSignedEventDao.getByChatRoomIdAndKind(parentRoomId, SubgroupBirthCertificateEvent.KIND)`
   returns, then `roomId`'s `GroupKeyState.birthCertificateJson`, then the
   certificates embedded in 30331 notices authored by `roomId`. It returns the
   first `parseName` of a certificate that passes `certifies(cert, roomId, parentRoomId)`.
2. **`GuardedSubject.Member` gains `guardianName: String?` and `branchName: String?`**,
   filled in `GuardianManager.named` beside `ownerName`. The name follows D2:
   founding name, then `chatRoomDao().findChatRoomById(id)?.chatRoom?.subject`,
   then null. Every caller of `guardedWalletsOf` gets the names for free, and no
   composable does a lookup. That matches what the member-wallets note set out
   for `ownerName`.
3. The branch is named the same way. Its parent is this group, so the first
   source always answers.

No query is new. `GroupSignedEvent` is indexed on `chatRoomId`, and one parent's
30329s are a handful of rows.

**Tests**, in `jvmTest`, `MemberWalletGuardianJvmTest`-shaped, with an
in-memory database:
- a device in R and P names M from P's signed 30329;
- a device in R only, with no notice for M's own wallet, gets `guardianName == null`
  and `branchName == "P"`;
- a 30329 for M signed by a room other than P is ignored;
- a renamed M (a new `subject` on a device in M) still shows the founding name.

---

## Phase 3 — the notice carries the certificate (D4, D5)

1. **`GuardedWalletNoticeEvent`** gains `certificateOf(notice): Event?`, which
   reads the tag and verifies nothing, matching `statementOf`. Its kdoc diagram
   gets the tag.
2. **`GuardianManager.share`** takes `certificate: Event?` and writes
   `["certificate", json]` after the `h` tag when one is given.
3. **`Shareable` gains `certificate: Event?`.** `shareableFrom(G)` fills it:
   - for G's own member certificates, from `GroupKeyState.birthCertificateJson`
     for G, which every device in G holds;
   - for wallets passed on a generation further, from the notice they came in
     on, so an R-and-P device forwarding to a great-grandparent hands it on.
4. **`guardedWalletsOf`** keeps each told row's tags, so the read becomes a pair
   of statement and carried certificate. When duplicates are folded, the copy with
   a certificate that checks out wins. That certificate goes to
   `foundingNameOf` as `carried`.
5. **`alreadyTold`** follows D5.
6. **`GuardianManager.describeNotice`** is unchanged. The line names the member, and
   the guardian is on the wallet screen.

**Mixed versions.** An older forwarder copies `content` only, so the tag is lost
at that hop. The next hop up then falls back to Phase 2's sources. Nothing is
believed less than before.

**Tests**, in `jvmTest`:
- a notice carrying M's 30329 lets a device in R only name M;
- a notice carrying a 30329 that certifies a different room, or one signed by a
  room other than `guardians[1]`, is ignored for the name, and the wallet is still
  listed;
- a notice with no tag, from today's build, is still believed;
- `shareableFrom` offers a room told before Phase 3 again when this device has a
  certificate, and does not when it has none;
- forwarding R ← P ← M keeps the tag at both hops.

---

## Phase 4 — the state: wallets by person

In `GroupWalletUIState.kt`, beside `GuardedWalletRow`:

```kotlin
/** One member's wallets as an ancestor's list gathers them -- D6. */
data class PersonGuardedWallets(
    val ownerPublicKey: HexKey,
    val ownerName: String?,
    /** Nearest first, then by guardian name. */
    val rows: List<GuardedWalletRow>,
) {
    val guardianCount: Int get() = rows.map { it.wallet.directGuardianChatRoomId.lowercase() }.distinct().size
}

/** [rows]' member wallets by person, people by name; subgroup rows are left out. */
fun List<GuardedWalletRow>.byPerson(): List<PersonGuardedWallets>
```

The `Loaded` and `NotOpened` states keep `guardedWallets` as they are, because
spend and fund still look rows up by it. The screen calls `byPerson()` on them.
There is no second field to keep in step while the chain flow redraws.

**Tests**, extending `GuardedWalletRowTest`:
- the example's two rows give one person with `guardianCount == 2`, Rent before
  Savings;
- two wallets under one guardian give `guardianCount == 1`;
- subgroup rows are not in the result;
- people sort by name, falling back to key.

---

## Phase 5 — the group's wallet screen

In `GroupWalletScreen.guardianItems`:

1. Subgroup rows render as they do now. Member rows are replaced by a
   *Members' wallets* sub-heading (`titleSmall`) and, for each
   `PersonGuardedWallets`:
   - **a header `ListItem`**: `ProfileAvatar` leading, `@alice` headline, and a
     supporting line from two new plurals,
     `n_guarded_wallets` and `guarded_by_n_groups`, joined with `s_dot_s` or a
     comma string. Tapping it opens `ProfilePreviewRoute`, as rows in the group
     page's *Members' guarded wallets* already do. It uses
     `Modifier.clickable { … }.minimumInteractiveComponentSize()`.
   - **one `GuardedWalletCard` per wallet**, indented under the header by
     `MaterialTheme.spacing.containerPadding`. Its headline and guardian line
     follow D6.
2. **New strings**, in sentence case, in `strings.xml`:
   `guarded_by_this_group`, `guarded_by_s_below_s` ("Guarded by %1$s, below %2$s"),
   `a_group_below_s` ("A group below %1$s"), `members_wallets`, and the two
   plurals. `guarded_by_s` already exists.
3. **The buttons' descriptions name the wallet, not the person.**
   `fund_s_s_guarded_wallet` and `spend_from_s_s_guarded_wallet` today take
   `guardedName`, which is `@alice` for all of Alice's wallets. A new
   `guardedWalletName(row)` gives `@alice · Savings`, or `@alice · guarded by #M`
   for an unlabelled wallet. The descriptions use it, so each of a person's
   buttons is heard differently.
4. **The spend and fund screens name it the same way.** `GuardedWallet.displayName`
   feeds `Spender.Guardian.name` and `ProposedSpend.nameOf`. For a member's
   wallet with a label, it becomes `@alice · Savings`, so the proposal a signer
   approves says which of Alice's wallets is being spent from. This is display
   text only. The signed proposal identifies the wallet by its guarded tag
   (`FrostSigningEvents.parseGuarded`), as it does today.

M3: spacing from `MaterialTheme.spacing`, colour from roles, the avatar's size as
a dimension. `readableContent()` is already on the list's root.

**Tests**, extending `GuardedWalletCardJvmTest`:
- the example draws one `@alice` header reading *2 wallets, guarded by 2 groups*,
  with *Guarded by #N* and *Guarded by #M, below #P* beneath it;
- an unlabelled wallet's headline is its guardian line;
- the four buttons have four different content descriptions;
- a wallet R guards directly reads *Guarded by this group*.

---

## Phase 6 — the person's page (D7)

1. `MemberWalletManager.guardedByGroupsHere` keeps its signature. A pure
   `byWallet()` over its result groups rows by `wallet.descendantChatRoomId`
   (that is, `W`) into:
   ```kotlin
   data class GuardedHereWallet(val walletId: String, val label: String?, val guardianName: String?, val holders: List<GuardedHere>)
   ```
   Holders are ordered nearest first, by `position`.
2. `PersonGuardedWalletsViewModel.rows` becomes `List<GuardedHereWallet>`.
3. `ProfilePreviewScreen.GuardedByYourGroups` draws one block per wallet:
   - the headline is the label, or the guardian line;
   - the supporting line is *Guarded by #M*;
   - one line per holder: *#P: after 144 blocks, about 1 day* (`guardian_after`,
     `delayText`), with *Spend* trailing where `canSpend`;
   - each *Spend* is described as *Spend from Savings as #P*, which is a new
     string.
4. `guarded_by_s` keeps its meaning. It now names the direct guardian, which is
   what its words always said.

**Tests**, extending `MemberWalletPagesJvmTest`:
- a device in P and R shows Savings once, guarded by M, with two holders;
- Rent shows once, guarded by N, with R as its only holder;
- a holder without a share has no *Spend*.

---

## Phase 7 — docs, the audit, and by hand

- **This note**: *Built*, plus a table of where the build chose differently.
  Done.
- [member-guarded-wallets.md](./member-guarded-wallets.md): Phases 10 and 11 get
  a pointer here. Phase 11's *Guarded by #Ekklesia* is changed to say that the
  holder now appears on the holder's own line.
- [group-wallet-guardians.md](./group-wallet-guardians.md): under *How an
  ancestor finds the coins*, a sentence on the notice's `certificate` tag.
- [README.md](./README.md): the row.

```bash
./gradlew :composeApp:m3Audit
```

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

By hand, on signet, with the example's tree and three emulators:

1. Alice asks M for Savings and N for Rent. Both are certified.
2. A member in M and P shares Savings into P, and a member in P and R shares it
   into R. A member in N and R shares Rent into R.
3. On a device in R **only**, R's wallet screen shows one `@alice` header,
   *2 wallets, guarded by 2 groups*, with *Guarded by #N* and *Guarded by #M,
   below #P*. The names come from the carried certificates, since this device is
   in neither M nor N.
4. Repeat step 2's forward into R from a build before Phase 3. The card reads
   *Guarded by a group below #P*.
5. On a device in P and R, Alice's profile shows Savings once, with #P and #R as
   holders, and Rent once, with #R.
6. TalkBack on step 3: each *Fund* and *Spend* names its wallet.

---

## Order and size

| phase | touches | ships |
|---|---|---|
| 1 | three properties on `GuardedWallet` | dark |
| 2 | `foundingNameOf`, `GuardedSubject.Member` names | dark |
| 3 | the notice's tag, `share`, `shareableFrom`, `alreadyTold` | **early**: the sooner notices carry it, the fewer rows fall back |
| 4 | `byPerson`, pure | dark |
| 5 | the group's wallet screen | **the visible change** |
| 6 | the person's page | any time after 2 |
| 7 | docs, audit, run | last |

Phases 1 to 3 change nothing a person sees. Phase 3 is worth landing before 5, so
that by the time the names are drawn, the notices sent in between already carry
them. Phases 5 and 6 are independent of each other.

---

## What this does not do

**It does not name a subgroup's wallet's parent.** A `#Translation` row could
read *below #P* by the same rule, using `branchChatRoomId`. That is a one-line
follow-up once Phase 5's strings exist, and is left out to keep this plan to the
question it answers.

**It does not draw the tree.** Distance and branch are enough to tell the cards
apart. A diagram of R's descendants would need every intermediate room's name,
and D3 gives no guarantee of those below the branch.

**It does not show a member wallet the ancestor was never told of.** An ancestor
sees what was forwarded to it. If Alice has a third wallet under a group whose
members never shared it upward, it is not in R's list and the count is two. The
header counts *wallets this group guards*, not *wallets Alice has*. Its wording
says so.

**It does not make the name part of what is signed.** The founding name helps a
person read the row. A wrong one, or a missing one, never changes which leaf this
group signs, or whether it can.

---

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| the live room name first | two members of R would see different words for one row, and subgroup rows in the same list already use founding names |
| the guardian's name in the 30334 | it is fixed when the member asks, and an ancestor would trust M's word about M's own name. The birth certificate is the parent's word, which is the one the list already relies on |
| putting the whole chain of 30329s on the notice | it names every room between this group and the guardian, but costs one certificate per generation on every hop. This group always knows the branch, and the branch plus the guardian is enough to tell cards apart |
| a separate *Members* tab on the wallet screen | it splits one question, "what can this group reach", across two places. The sub-heading keeps it on one list |
| one card per person, wallets as rows inside it | each wallet has its own *Fund* and *Spend*, and four buttons in one card is the crowding `guarded-wallet-funding.md` Phase 5 moved them out of the trailing slot to avoid |
