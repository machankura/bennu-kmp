# Guardians: an ancestor group's leaf in a subgroup's wallet

A subgroup's onchain wallet can carry its ancestry in its addresses: one taproot
leaf per ancestor group, each spendable by that ancestor's quorum, so the root of
a hierarchy can move the coins of any group beneath it. The child's own spends
stay on the key path, look like any single-key taproot spend, and never reveal
that the tree exists.

Read [subgroups.md](./subgroups.md) and [group-chain-wallet.md](./group-chain-wallet.md)
first. This builds on the birth certificate from the first and the account
derivation from the second, and it changes one sentence in each.

**Built**, phases 1-9, one commit each, written against the code as it stood at
`409be6db` with the `lightning-kmp-app` submodule at `e0d2b82`. `GuardianDerivation`,
`GuardianManager` and `FrostSigningManager.proposeGuardianSpend` are the entry
points, and `GroupWalletRollout.GUARDIANS_ON_MAINNET` is the gate. The phases
below are kept as written; where the build chose differently the table says so.

| what the plan said | what it turned out to be |
|---|---|
| Phase 4 is the statement, Phase 5 the addresses | Phase 4 did both: a statement cannot be verified without rebuilding the descriptors it carries, so `Account` learned its guardians and `deriveBelow` its tree in the same commit |
| Phase 9 adds the rollout gate | the gate came with Phase 5, because that is where a guarded wallet is first offered; a way in shipped before its gate would have been offered on mainnet for four commits. Phase 9 is this table |
| forwarded statements are stored for the ancestor to watch | no table: the 30333 notice is the `MarmotInnerEvent` row the inbound path files for every inner event, and `GuardianManager.guardedWalletsOf` checks each one again on every read -- the economy `GroupWallet` already has |
| the proposal carries `["input", …]` tags | it carries one tag, `["guarded", <descendant>]`. Each input's `tap_bip32_derivation` already names its place under the descendant's account, exactly as the child's own spend does, so a second place to say it would be a second thing to disagree |
| `check` asks the proposer's device whether a coin has 12 confirmations | the builder does: it only offers a guardian coins its lock has already let go of. `check` cannot ask, because a signer with no node has no tip, and a coin spent too early is a transaction nobody can mine rather than a loss |
| `GroupTransactionWatcher` notices a guardian's spend | the wallet screen's view model does, each time the guarded wallet's coins change, by reading every used address's history off Electrum -- so a device asleep when the coin moved still finds it, and whichever device reads first writes the one line. A device that never opens the wallet screen writes nothing |
| schema unchanged | v28 (v27 on this branch until the default branch's document-table removal took that number): `GroupTransaction.guardedChatRoomId`, the descendant a guardian's spend draws from. A spend's account is otherwise read from the statement, and a guardian's is the descendant's statement, not the room's |
| a spend that lost its coins to the other side is never settled -- this note's own *What this does not do*, as first written | settled. `GroupTransactionWatcher` asks the chain, for a signed spend Electrum has not heard of, whether each coin is still unspent at its address and, if not, which transaction in that address's history took it; once that transaction is confirmed, `FrostSigningManager.recordReplaced` marks the spend replaced and settles every other spend of the coin, as `recordConfirmed` does for the room's own. A conflict still in the mempool settles nothing |
| nothing outside the plan | a bug older than it: bitcoin-kmp reads a taproot derivation's fingerprint back from a PSBT as a signed int, so for half of all accounts every member but the proposer refused every spend as not the wallet's own. Fixed on its own, in `fix(wallet): read a PSBT's key fingerprint as unsigned`, because it reaches every plain wallet too |

**The mainnet gate is open.** It was to lift on a guardian spend made on signet
by a real three-level hierarchy, every member's device watching, and the
grandchild's transcript naming the root; it was lifted by decision before that
run. The run is still worth making, and the mixed-version risk below is still
open: a group whose members are not all on a build that reads guarded statements
should not open one. Guardian spends on mainnet are held to the same per-spend
cap as every other spend, `GroupWalletRollout.MAINNET_CAP`.

The headline: **the parent puts a guardian key for the child into the child's
birth certificate, beside the keys its own ancestors gave it, each derived one
public step further for this child. Each becomes a leaf
`<L> OP_CHECKSIGVERIFY <d> OP_CHECKSEQUENCEVERIFY` in every address of the child's
wallet.** Every ancestor can take a coin once it has `d` confirmations, where `d`
is the delay that ancestor chose when it made the group below it — 12 unless it
chose otherwise, see [guardian-delay.md](./guardian-delay.md). The
ancestor's quorum signs for its leaf with the FROST session it already runs,
under plain tweaks of its own threshold key.

---

## What this reverses, and whose choice it is

[subgroups.md](./subgroups.md#what-this-does-not-do) says:

> A subgroup cannot sign for its parent, and the parent cannot sign for it. […]
> If delegation is ever wanted it is a different mechanism and it should not be
> built on top of this one silently.

This is that mechanism, and it is not silent. It takes two signatures, one from
each side:

- the **parent's** quorum signs the guardian keys into the certificate, so the
  parent agrees to be a guardian and every one of its signers' devices has
  checked the keys it is vouching for;
- the **child's** quorum signs a wallet statement that names them, so the child
  agrees to be guarded.

The certificate on its own delegates nothing. A child that never opens a guarded
wallet has a certificate with keys in it and no address that uses them.

**The parent decides, and the chain does not enforce it.** Since
[subgroup-wallet-policy.md](./subgroup-wallet-policy.md), the parent decides when
it makes the child: the coordinator picks *guarded* or *unguarded* on the
subgroup picker, every admin is told at the join gate, the parent's quorum signs
the choice into the certificate as a `["wallet", …]` tag, and the child's quorum
signs it again in its key state. Every client running this app then offers,
signs, derives and watches only the wallet the certificate allows. A guarded
group's subgroups are all guarded.

What has not changed is the cryptography. The child's threshold key is the
child's. Its quorum can always pay to a plain BIP86 address under its own key,
and no script in any address can stop it. The parent's decision is a signed rule
every honest client keeps, not a rule the chain enforces, and the UI never
describes it as more than that.

A certificate from before policies carries no `wallet` tag and decides nothing.
Its child is offered a guarded wallet by default when the certificate carries
keys, and a plain one beside it, as this note first described. There, being
guarded is still the child's own choice.

**The child gives up a real thing.** An ancestor can take any coin in the wallet
that has been confirmed for two hours, without the child's agreement and without
warning — the first the child learns of it is a spend none of its members
signed. That is the honest description of "the root can spend from its
descendants".

---

## The shape of an address

```
             Q  = P + H_TapTweak(P ‖ root)·G             output key
            ╱ ╲
  key path: P                                   the child's own key, as today
                       root
                      ╱    ╲
          leaf(parent)      •
                           ╱ ╲
             leaf(grandparent) •
                              ╱ ╲
                            …    leaf(root group)
```

- `P` is exactly today's internal key, `account(K_child, network, n)/change/index`.
  A spend by the child is a key-path spend, signed exactly as today; the only
  change on that side is that the final tweak is BIP341's script-path tweak over
  `root` rather than BIP86's empty one.
- The leaves form a caterpillar, nearest ancestor shallowest. Every ancestor can
  act at the same moment, so the order decides only cost: the parent's control
  block is the shortest and the root's the longest, at `33 + 32·(k − 1)` bytes
  for `k` ancestors.
- One ancestor is a tree of one leaf, with a 33-byte control block.

### The leaf

```
<L> OP_CHECKSIGVERIFY 12 OP_CHECKSEQUENCEVERIFY
```

Miniscript's `and_v(v:pk(L),older(12))`. The delay is a BIP68 relative lock in
blocks and counts from the confirmation of the coin being spent, not from
anything the group did: a coin with 12 confirmations is the guardian's to take,
and one that arrived or was moved within the last two hours is not. `12` is the
single opcode `OP_12`, so the script is 36 bytes.

**Why a delay, and who sets it.** This section was *Why 12, and why for
everybody*: 12 was one constant, the same at every distance. Since
[guardian-delay.md](./guardian-delay.md), each parent chooses its own leaf's delay
when it makes a guarded subgroup, from `GuardianDerivation.MIN_DELAY` (6, about an
hour) to `MAX_DELAY` (65,535, BIP68's block-based ceiling, about 15 months), with
12 as `DEFAULT_DELAY`. Every inherited key keeps the delay its own ancestor chose,
so each group's terms follow its key down the tree. What 12 buys, and what a floor
of 6 keeps:

- **Not autonomy, at 12.** Two hours is not long enough for a child to notice a
  coin coming within reach and move it. In practice this is an override, and it
  is meant as one: ancestors have control now, not as a last resort. A long delay
  changes that: the clock is per coin and moving a coin restarts it, so a child
  that moves its coins before a month-long lock runs out keeps them out of reach.
  A long delay is a backstop for a group that falls silent, not an override.
- **The child's own spends always go first.** A guardian cannot race the child
  for a payment that has only just arrived, nor take change from a spend the
  child made an hour ago.
- **It is a relative lock**, so every guardian input carries `nSequence = d` in
  a version-2 transaction. That is far below `GroupSpend.MAX_SEQUENCE`, so a
  guardian spend still signals replaceability and *Bump the fee* still works.

The delay is written into every `guardian` tag rather than assumed, so a later,
different delay is a later account's tags, not a new format.

---

## The guardian keys

### Each group has one guardian root, which it never hands out

```
I     = HMAC-SHA512(key  = "bennu/group-wallet/guardian/v1",
                    data = serP(K_A))
R_A   = K_A + I[0:32]·G            plain tweak, like the wallet's account step
c_A   = I[32:64]

(R_A, c_A)                          A's guardian root, an xpub at depth 0
```

The same shape as `GroupWalletDerivation.account` without the network and the
account number, which come lower down. It is not the room key: a room id is the
`h` tag on every message the room publishes, so a relay knows it, and a leaf
built on it would tie an onchain spend to a Marmot room for anyone who has
watched the relay. Nothing about `R_A` is published anywhere.

### A child's index comes from its room id

```
i(C) = taggedHash("bennu/group-wallet/guardian/child/v1", C)[0:4]  &  0x7fffffff
```

Unhardened, so anyone holding the parent's copy of an xpub can take the next
step, and nobody holding only the child's copy can go back up or across to a
sibling.

### What A writes into C's certificate

```
guardians(C) = [ R_A / i(C) ]                                 A's own key, granted to C
             ++ [ g / i(C)  for g in guardians(A) ]           A's ancestors' keys, as A received them
```

Nearest first, each tagged with the room it belongs to. A root group — or any
group whose own certificate carries no guardian keys — writes only its own entry.

For root → middle `M` → grandchild `C`:

```
cert(M), signed by root:  [ R_root/i(M) ]
cert(C), signed by M:     [ R_M/i(C),  R_root/i(M)/i(C) ]
```

Re-deriving each inherited key at every level is what keeps a parent's wallet and
its child's from sharing a leaf: the root's key in `M`'s wallet is
`…/i(M)/n/a/…` and in `C`'s it is `…/i(M)/i(C)/n/a/…`. And siblings cannot link
each other: `C₁` holds `R_A/i(C₁)` and cannot compute `R_A/i(C₂)` without `R_A`'s
chain code.

`i(C)` is 31 bits, so two siblings could share an index; the odds pass even at
around 55,000 children of one parent, and the consequence is only that those two
siblings' guardian leaves become linkable onchain.

### The leaf for one of C's addresses

At network `n`, account `a` and address `change/index`:

```
L = xonly( guardian / n / a / change / index )
        n: 0 mainnet, 1 testnet3, 2 testnet4, 3 signet, 4 regtest
```

So the root's leaf in `C`'s receive address 7 of mainnet account 0 is
`R_root / i(M) / i(C) / 0 / 0 / 0 / 7`. Everything below the guardian root is
ordinary CKDpub, so the descriptor stays one Bitcoin Core imports:

```
tr([fp]xpub_C/0/*,{and_v(v:pk(xpub_M→C/0/0/0/*),older(12)),and_v(v:pk(xpub_root→C/0/0/0/*),older(12))})
```

### Who checks what

- **When A's quorum signs C's certificate**, each signer's device rederives
  `R_A/i(C)` from `K_A`, and checks every inherited entry is exactly the matching
  entry of A's own verified certificate one step further, in the same order. A
  coordinator cannot add, drop or swap a key without every honest signer's
  device refusing the proposal.
- **The child trusts the parent's quorum for the inherited entries**, and it is
  no new trust: that quorum can already take every coin through its own leaf.
  A reader holding the parent's certificate can check them anyway, since each is
  a public step from an entry one certificate up.
- **When ancestor X signs for a coin in descendant C**, its FROST cache is plain
  tweaks all the way down the line of descent from X:

```kotlin
TweakCache.create(K_X)
    .tweak(I[0:32],    isXonly = false)   // guardian root
    .tweak(IL(i(A1)),  isXonly = false)   // one step per generation, X's child first
    …
    .tweak(IL(i(C)),   isXonly = false)
    .tweak(IL(n),      isXonly = false)
    .tweak(IL(a),      isXonly = false)
    .tweak(IL(change), isXonly = false)
    .tweak(IL(index),  isXonly = false)
// xonly(cache.tweakedPublicKey) == L; no TapTweak, since it signs as the leaf key itself
```

  X reads the line of descent off the room ids in C's certificate, and checks
  that the entry there for X is the key this walk reaches before it signs
  anything. There are no x-only tweaks before the last step, so there is no
  parity to get wrong.

---

## The statement

The opt-in is the child's `GroupWalletStatementEvent`, signed by its quorum as
today, with two kinds of tag added:

```
["guardian", <ancestor room id>, <key hex>, <chain code hex>, "12"]   one per ancestor, nearest first
["birth_certificate", <the child's certificate, as JSON>]
["descriptor", tr(…,{…}), "receive"]                                  as today, with the tree
["descriptor", tr(…,{…}), "change"]
```

`verifiedAgainst` grows the same way: it rederives the account from `K_child`,
checks the certificate certifies this room, checks the `guardian` tags are
exactly the certificate's entries with the delay this build writes, rebuilds the
tree, and compares the descriptors. The statement stays *a record that the group
agreed, not a source of keys*. Carrying the certificate makes it self-contained:
a member welcomed into the child after its birth, who holds no key state, can
still check every address — the gap [subgroups.md](./subgroups.md#what-this-does-not-do)
names, closed for the wallet.

Three rules, each closing a way to lose coins:

1. **A guarded statement outranks a plain one for the same account.** An account
   opened guarded stays guarded; a later plain statement for it, from a client
   that predates this, is ignored rather than allowed to move every address.
2. **A guardian set is fixed for its account.** Among guarded statements for one
   account the earliest decides the guardians, and a later one naming others is
   ignored. Changing who guards a wallet is another account.
3. **Old clients refuse it**, which is the right failure: `verifiedAgainst`
   compares the descriptors byte for byte, and theirs have no tree, so a client
   that predates this rejects the statement rather than handing out unguarded
   addresses under the guarded account's name.

What an old client can still do is see no wallet and open a plain one. Rule 1
keeps every new client on the guarded addresses, but the old client then hands
out plain ones that new clients do not watch. That is a mixed-version risk, and
it is why the guarded wallet first shipped behind a gate shut on mainnet -- since
opened, so the risk is now the group's to manage.

### The rule from the wallet note, restated

[group-chain-wallet.md](./group-chain-wallet.md#why-each-level-is-what-it-is)
says having no script tree is a security property: a member can check alone that
no back door is hidden in an address. That stays true with one word changed —
**every member derives every address, tree included, and compares it byte for
byte.** The tree is a pure function of the child's key, the account and a
certificate every member verifies, so a proposer still cannot slip a leaf in.

---

## How an ancestor finds the coins

To spend from a descendant, an ancestor's members need the descendant's account
xpub and its tree. Both are in the descendant's guarded statement, which checks
out against things the ancestor can verify alone: it is signed by the
descendant's room id, the certificate inside it is signed by the descendant's
parent and certifies that room, the certificate's `subgroup_key` is the key the
xpub rederives from, and the guardian entry for the ancestor is the key its own
walk reaches.

What the ancestor does not have is the statement: it is signed in the child's
room, and the root's members are not in it. So a member who sits in both rooms
**forwards** it, as an ordinary message in the ancestor's MLS room — never to a
relay, under the same `NEVER_BROADCAST` rule. The forwarder is trusted with
nothing but the act of forwarding.

That depends on membership overlapping, which it does at birth — a child's
admins are chosen from its parent's members — and may not later. A grandchild
whose middle group no longer shares a member with the root has a root leaf in
every address and a root that cannot find a coin to use it on. A statement
forwarded into the middle group can be forwarded again from there, so overlap is
needed one level at a time, not between the grandchild and the root directly.

Since [member-guarded-wallets.md](./member-guarded-wallets.md) the thing
forwarded may also be a member wallet certificate, which the guarding group
signed itself and so needs no forwarding into that group. Since
[member-wallet-guarding-group.md](./member-wallet-guarding-group.md) the notice
carrying one also carries, as a `certificate` tag, the guarding group's own
birth certificate, so a group above that was never in its room can name it.
Like the statement, the tag is checked rather than trusted, and an older build
ignores it.

Once it has the statement, the ancestor watches the descendant's addresses on the
Electrum client it already holds — unknown script hashes are ignored, so this
costs subscriptions and nothing else — and its wallet screen grows a *Guarded
wallets* section.

---

## A guardian spend, end to end

An ancestor's FROST session in the ancestor's room, with the existing proposal,
approval and completion steps, and three differences.

**The claim rides in tags, not PSBT fields.** bitcoin-kmp does not parse
`tap_leaf_script`, `tap_merkle_root` or `tap_script_sig` — they land in
`unknown`, which is why `GroupSpend.check` refuses any input carrying them.
Rather than teach the library those fields, keep the refusal and put the claim
where a spend's other claims already go: the proposal names the descendant
wallet, and each input's `tap_bip32_derivation` names its place under the
descendant's account, exactly as a child's own spend does. Every signer then
rebuilds the leaf, the tree, the control block and the script-path sighash from
the forwarded statement it holds. A proposer who names the wrong place gets a
sighash nobody else computes.

**Sequence and version.** `check` grows one rule: a guardian input's sequence is
exactly 12, and — on the proposer's device, which can see the chain — the coin
already has 12 confirmations, so a spend that cannot go in the next block is not
put in front of a quorum. Signers without a node rely on the script: a coin
spent too early is a transaction nobody can mine, never a loss.

**The witness** is `[sig, script, control block]`, from
`Script.witnessScriptPathPay2tr`, and `correctlySpends` is still the last word.

### What the child sees

Nothing until it happens. A child's device reads the history of every address
its guarded wallet has used, finds a transaction whose witness carries one of
its guardians' leaves for the coin it spent, and writes a transcript line saying
which ancestor took how much -- `GuardianManager.takenIn` and `recordTaken`.

### What it costs onchain

A key-path input's witness is 66 weight units. A guardian input with one ancestor
is about 137 — the signature, the 36-byte script and a 33-byte control block —
so about 18 vbytes more, and 8 vbytes more for each further level of depth. Only
paid when a guardian spends; the child's own spends cost exactly what they cost
today.

### What it reveals

| spend | a chain observer learns |
|---|---|
| the child, key path | nothing beyond any single-key taproot spend, as today |
| a guardian | that the output had a script tree; the one leaf used and its delay; its depth in the tree, so a lower bound on the hierarchy's depth. The leaf key is unlinkable to any room id |

---

## Phases

One commit each.

1. **`GuardianDerivation`**, pure, commonMain: `ANCESTOR_DELAY`, the guardian
   root, the child index, the grant, the leaf key, script and tree, the
   descriptor fragment, and the signing cache. Vectors: the root and grant by
   point arithmetic and by cache, agreeing; a three-level grant; a tree of one
   and of three; the descriptor through bitcoin-kmp's checksum.
2. **Signing a leaf.** A jvmTest in the shape of `GroupSpendSigningJvmTest`: a
   parent ceremony's shares sign a script-path spend of a child's coin, and
   `correctlySpends` accepts it at sequence 12 and refuses it at 11.
3. **The certificate carries the keys.** `guardian` tags on
   `SubgroupBirthCertificateEvent`, written by `proposeBirthCertificate` from
   the parent's key and its own verified certificate, and checked by every
   parent signer before a certificate proposal is accepted.
4. **The guarded statement.** Tags, `read`, `verifiedAgainst`, an `Account` that
   knows its guardians, and the three rules in `GroupWallet`.
5. **The child's side.** Addresses with the tree, `GroupSpend` checking against
   them, and the wallet screen opening a guarded wallet when the room's
   certificate carries guardian keys.
6. **Forwarding upward**, and the ancestor watching what it was forwarded.
7. **The guardian spend**: the builder, the proposal, `GroupSpend`'s guardian
   arm, `FrostSigningManager` signing with the guardian cache over the
   script-path sighash, and the witness at completion.
8. **The child's notice**: the watcher naming the ancestor that spent.
9. **Rollout**: `GroupWalletRollout.GUARDIANS`, on for test networks, shut on
   mainnet until a guardian spend has been made on signet by a real three-level
   hierarchy.

---

## What this does not do

**It does not, on its own, guard one person.** That is
[member-guarded-wallets.md](./member-guarded-wallets.md): a wallet whose key
path is one member's random key, carrying exactly the leaves a guarded subgroup
of their group would, under a member wallet certificate (30334) the group signs
over the member's own request. Forwarding, the ancestor's walk and the guardian
spend are the ones here, with the line of descent ending in the wallet's id.

**It does not guard a wallet that is already open.** A subgroup that opened a
plain wallet before its certificate carried keys keeps it. Guarding it would be a
second account and a spend into it, and the wallet screen shows one account per
room today.

**It does not reach a subgroup certified before this.** Its certificate has no
keys. Its parent can sign a fresh one — certificates are replaceable per child,
newest winning — but nothing here prompts it to.

**It does not, on its own, let the parent decide.** That is
[subgroup-wallet-policy.md](./subgroup-wallet-policy.md): a policy the parent
signs into the certificate at the child's birth, which the child's own quorum
agrees to.

**It does not survive a re-keyed ancestor.** A leaf is bound to the ancestor's
threshold key, so to the shares of the ceremony that made it, including the
shares of members who have since left. This is the wallet note's *removal from
the group does not un-know a key*, inherited one level down.

**It does not tell the child before it happens**, and it does not constrain where
the coins go. "Only back into the child, at a new address" is a covenant, and
there is none on mainnet to write it with (`OP_CHECKTEMPLATEVERIFY`,
`OP_VAULT`). The other direction is supported: an ancestor paying coins *into*
a guarded wallet is [guarded-wallet-funding.md](./guarded-wallet-funding.md).

## Appendix — what was considered and rejected

| idea | why not |
|---|---|
| derive the child's key from the parent's | the child is then the parent wearing a different hat — [subgroups.md](./subgroups.md#why-the-key-is-fresh-rather-than-derived) |
| the ancestor's room key as the leaf key, tweaked by the child's chain code | the parent never agrees to anything, the child has to find every ancestor's room key itself, and the first tweak has to be x-only to agree on parity. A key the parent signs into the certificate fixes all three |
| the ancestor's threshold key `K_A` | the child does not have it, and handing it over gives every child member the ancestor's whole wallet and room identity |
| a `k`-of-`n` `OP_CHECKSIGADD` over the ancestor's admins' own keys | it reveals the admins onchain, changes with every membership change, and bypasses the quorum the ancestor's FROST key already is |
| passing inherited keys down unchanged | a grandchild's leaves would equal its parent's wallet's at the same index, and a guardian spend from one would link the two |
| the whole certificate chain in the statement | nobody needs it to be safe — the parent's quorum vouches for the inherited keys and already holds more power than they give — and nesting signed events inside signed events escapes JSON once per level |
| the tree in PSBT fields | bitcoin-kmp does not parse them; every signer rebuilds them anyway |
| delays escalating with distance | asked for and replaced: every ancestor at 12 blocks, for now. Since [guardian-delay.md](./guardian-delay.md) each ancestor's delay is its own choice instead |
