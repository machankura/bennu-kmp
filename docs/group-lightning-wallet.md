# A group Lightning wallet

A Lightning wallet that belongs to a group. Its channels' funding key is an
Iceberg threshold key, which no member, no operator and no `t - 1` of them hold
alone. One member at a time, the **operator**, runs the lightning-kmp node on
their device and tells the rest of the group what it holds. When a channel
closes, the money goes to the group's onchain wallet. A group without an onchain
wallet cannot open one of these.

**Plan, not built.** It was written against `409be6db`, with the
`lightning-kmp-app` submodule at `e0d2b82`, lightning-kmp at `e5b22f5d` (branch
`threshold`), and the bitcoin-kmp and secp256k1-kmp forks beneath it. Read these
first:

- [group-chain-wallet.md](./group-chain-wallet.md): every phase here assumes the
  onchain wallet, its derivation and its spend checks.
- [shared-key-ceremony.md](./shared-key-ceremony.md): the Iceberg ceremony in
  Phase 5 is modelled on it.
- `lightning-kmp-app/experimental/lightning-kmp/FEATURE-PARITY.md`: how the fork
  put Iceberg behind lightning-kmp's `FundingSigner` seam, and what that port
  calls out of scope. This plan is that out-of-scope work.
- `…/secp256k1-kmp/native/secp256k1/doc/iceberg.md`: the scheme's own rules.
  Four of the constraints below are quoted from it.

### Where the build stands

One commit per phase. The phases below are kept as they were written; where the
build chose differently, this table says so.

| phase | state |
|---|---|
| 0 | done, except the two spikes (see below) |
| 1 | done: `IcebergDkg`, 11 tests, among them a byte-for-byte match against the library's dealer and a signature from every 2-of-4 and 3-of-7 quorum |
| 2 | done, in the fork (`4bc973df`): the three signing methods suspend, `RemoteIcebergFundingSigner`, pings answered outside the channel loop. lightning-kmp's JVM suite, 985 tests, passes |

| what the plan said | what it turned out to be |
|---|---|
| Phase 0's two spikes run against the testnet3 LSP | **not run.** Both need a channel that somebody pays into, and the regtest alternative means creating docker containers on a development machine that already runs other stacks. That should be somebody's deliberate act, not a side effect of a build. The signer's deadline is therefore a parameter, `RemoteIcebergFundingSigner.deadline`, and not a measured constant. Measuring it is the first thing to do with a funded testnet3 node |
| the DKG goes in the bitcoin-kmp fork, beside `Iceberg.kt` | in the app, as `IcebergDkg` beside `GroupWalletDerivation`. It uses only `Iceberg`'s public API and SHA-256, so it needs nothing from inside the library, and putting it there would add a third nested fork branch to every bump. It moves down once it has been reviewed |
| a complaint round is left out, so equivocation is caught but not blamed | a reveal that does not match its commitment *is* blamed, as `Fault.BadReveal`. Only committing to two values in front of two members goes unblamed, and the transcript signature still catches it |
| every `FundingSigner` method suspends | only the three that sign. `verificationNonce` and `publishedNonceSession` stay synchronous, because one is called from `InteractiveTxSession`'s secondary constructor and fifty-one call sites build sessions that way. Round one is served from a pool the group fills ahead of time, so nothing there has to wait. An unprovisioned label throws `IcebergNonceNotProvisioned`, and the operator's node must not connect while its pool is short (Phase 8) |
| `Peer` answers `Ping` in its own coroutine | in `Peer.send`, before the command is queued, whenever the ping arrives on the adopted connection. The receive loop goes through `send` too. A ping that arrives before the connection is adopted falls through to the old handler |
| a channel that times out disconnects | the signer returns `IcebergSigningTimeout` and the channel treats it as any failed signature. Disconnecting is the operator node's response (Phase 8), not lightning-kmp's |
| a test drops one answer in ten | a test with a group that never answers, and checks the retry gets a new label. The channel suite runs with random delays, and asserts after every test that no label was asked to sign two messages |
| the kinds are reserved | in code: `GroupLightningEvents`, 30333-30349, with a test asserting the run is unbroken and touches no kind the app already dispatches on |
| library phases land in the forks' `threshold` branches | on a new branch, `claude/group-lightning-wallet`, in `lightning-kmp-app` and in `experimental/lightning-kmp`. It is local and not pushed. The app's submodule pointer follows it, so a checkout of this branch needs those commits from the same machine until they are pushed |

The headline: **Iceberg on the funding key is necessary but not enough.** The
fork's port is an in-process simulation: every share sits in one process, the
signer answers synchronously, and the commitment outputs that pay this node are
keyed from the node's seed. Three things have to be built before a real group can
use it:

1. **A distributed key generation.** Iceberg ships only a trusted dealer.
2. **An asynchronous signer with a group agreement step in front of it.** The
   operator is Iceberg's coordinator, and the scheme assumes the coordinator is
   hostile.
3. **Group-held keys on the outputs a closing channel pays to us.** Without
   them, whoever holds the node seed can take the group's balance by getting the
   LSP to force-close.

The rest is ordinary app work: an election, a handover, snapshots, and
payments.

---

## What was asked, and what exists

| asked for | what exists | what is missing |
|---|---|---|
| a Lightning wallet signed with Iceberg | `IcebergSigner` and `IcebergFundingSigner` in lightning-kmp `commonMain`, behind `FundingSigner`. The channel suite passes with a 2-of-4 group held in one process | a DKG, a signer over the wire, and nonce labels the group agrees on |
| regenerate keys, because Iceberg shares are not FROST shares | the group's ChillDKG key and `DkgSession` | an Iceberg ceremony with its own session table |
| no Lightning wallet without an onchain wallet | `GroupWalletStatementEvent` (30331), with `GroupWallet.of` to check it | a statement for the Lightning wallet that names the onchain account it closes to |
| the onchain wallet is the final wallet | `GroupWalletDerivation`, `GroupOnchainWallet.receiveAddress` and the FROST spend machinery | lightning-kmp closing to a group address, and group keys on to_local and to_remote |
| elect an operator | nothing: the group's only way to decide anything is a FROST-signed event (`GroupSignedEvent`) | an operator statement with a term number |
| the operator shares balance, channels and payments | the app runs a Phoenix `Peer` per identity but uses none of its Lightning features | a second node per group on the operator's device, and snapshots sent over MLS |
| one operator at a time; the previous one stops | nothing | a handover, and a fence the old device cannot get round |

---

## The constraints the plan follows

### 1. Iceberg shares cannot be made from FROST shares

A ChillDKG share is a point on a polynomial: one scalar. An Iceberg share is
replicated secret sharing (RSS). Every `(t-1)`-subset of participants has its own
32-byte seed, and every participant outside that subset holds it. A participant
holds `C(n-1, t-1)` seeds, in lexicographic rank order of the subsets
(`src/modules/iceberg/rss.h`). Nothing turns a scalar into those seeds. So a group
that wants a Lightning wallet runs a second ceremony and gets a second,
independent key. The onchain wallet and the room's ChillDKG key are untouched.

### 2. There is no Iceberg DKG

From `secp256k1_iceberg_dealer.h`: *"Anything else wants a distributed key
generation … This module does not provide one."* The signing API does not care
where a share came from, as long as it arrives through `share_parse`. So a DKG can
build the seeds itself and hand the serialised bytes (`version | n | t | index |
seeds`) to `share_parse`. RSS makes a DKG simple: each seed only needs a random
contribution from each of its holders. That DKG is still new cryptography written
for this plan, and Phase 1 treats it that way.

### 3. Only some group sizes work

| bound | source | effect |
|---|---|---|
| `t ≤ (n+1)/2` | `2t-1` members have to exist | 2-of-2, 3-of-4 and 4-of-6 cannot be expressed |
| `n ≥ 3t-2` | Byzantine agreement on what is being signed, from `iceberg.md` | 2-of-3 and 3-of-5 can be expressed but are not deployable |
| `n ≤ 10` | `SECP256K1_ICEBERG_MAX_PARTICIPANTS` is part of the ABI | no large groups |
| `t ≥ 2` | this plan | 1-of-n is a shared single key |

The configurations this app will offer are therefore 2-of-4 through 2-of-10,
3-of-7 through 3-of-10, and 4-of-10. A group whose onchain wallet is 2-of-3
cannot have a Lightning wallet until it has four members. The Lightning
threshold is chosen in its own ceremony and does not have to match the onchain
one. The ceremony screen says why a size is refused, rather than leaving it off a
list.

### 4. Every payment needs `2t-1` devices to answer, twice

Round one needs `2t-1` members, and so does round two (`iceberg.md`: *"Reading
that as 'round two needs t people' is the mistake to avoid"*). The fork measured
what one payment costs on the group side: **six round ones and two round twos**.
Opening a channel costs five round ones and one round two. This is true for
receiving as well as sending, because each side signs every commitment update.

Two consequences follow, and neither one can be designed away:

- **Members' devices sign without a person in the loop.** Nobody approves sixteen
  messages per payment by hand. Each device runs a **co-signer** (Phase 9) that
  answers under a policy. People approve *payments*; devices approve
  *commitments*.
- **The wallet only works while `2t-1` co-signers are online.** For 2-of-4 that
  is three of four devices, one of which may be the operator's. Round one does not
  depend on the message, so it can be computed ahead of time (Phase 2). Round two
  cannot, so a payment waits on the slowest of `2t-1` devices, twice.

### 5. One label signs one message, across the whole group

Iceberg derives nonces from a session label instead of storing them. If one label
is answered under two different messages, the share leaks, and no error is
raised. The scheme's docs say a member's own record is not enough:

> a coordinator can show three *different* members three *different* messages
> under one label, and each one signs exactly once, refuses nothing, and detects
> nothing.

The operator is the coordinator. It moves messages and aggregates. `iceberg.md`
says *"Every check in the module assumes it is hostile"*, and so does this plan.
Members therefore **agree on (label, message) before anyone answers round two**.
That is the echo rule in Phase 9.

### 6. The seam is synchronous and runs inside the peer's message loop

The `FundingSigner` methods return a value straight away. They are called from
`ChannelState.process`, which is `suspend`. `Peer` calls `process` from the same
`when` that answers `Ping`. A signer that waited minutes for phones would stop the
node answering pings, and the LSP would disconnect it. Phase 2 makes the seam
suspend and moves keep-alive out of that loop.

### 7. The funding key is not the only key that holds money

In a simple taproot channel, the output that pays us in the LSP's commitment is
`pay2tr(NUMS, <our payment basepoint> OP_CHECKSIGVERIFY 1 OP_CSV)`
(`Scripts.kt:332-350`). Our output in our own commitment is keyed from our
delayed payment basepoint. In `LocalKeyManager` both basepoints come from the node
seed. So whoever holds the seed can send the LSP an `error`, wait for it to
force-close, and spend our balance one block later. A threshold funding key does
not stop that. Phase 3 replaces both basepoints with keys from the group's onchain
FROST key. After that, a channel that closes badly pays the group, and only a
FROST quorum can move the money.

### 8. Experimental, and the library says so

`iceberg.md`: *"Do not put money behind this module."* The proof is in a working
draft, and the known ways to lose a key are all in the label rule above. The DKG
in Phase 1 is newer still. Mainnet is refused in code until both have been
reviewed outside the project (Phase 15).

---

## Decisions

| | decision | why |
|---|---|---|
| D1 | **The Iceberg participants are the onchain wallet's ChillDKG participants**, with their own `t`, in one ceremony per Lightning wallet | the people who can move the onchain money are the people who sign its channels. A subset would make the closing destination answerable to a different quorum from the channel |
| D2 | **A Lightning wallet requires a verified onchain wallet statement on the same chain.** Its statement names that `(chain, account)`, and every closing output pays an address derived from it | this is the final-wallet rule, and a co-signer checks it by deriving the address itself |
| D3 | **The operator is the Iceberg coordinator and is trusted for liveness only.** It is elected by a FROST-signed statement with a strictly increasing term. A member can only nominate themselves | the elected device has to run a node, so nobody is elected without agreeing to it. The term is what every co-signer fences on |
| D4 | **One node seed per Lightning wallet, shared with every Iceberg participant** inside the room, and bound to the statement by its node id | a handover then keeps the channels: the new operator restores from the LSP's peer storage (`NodeParams.usePeerStorage`). It also means an operator who loses their phone does not take the channels with them. With D5 in place, the seed no longer holds the group's balance |
| D5 | **The payment and delayed-payment basepoints are group FROST keys.** lightning-kmp never sweeps them. The group does, with a FROST script-path spend into the onchain wallet | constraint 7. This is the final-wallet rule applied to a channel that closes badly |
| D6 | **People approve outgoing payments, closes and splices.** Each is a FROST-signed authorisation through the existing proposal screens. Co-signers sign commitments automatically when they fit an authorisation | constraint 4. The authorisation is a group-signed fact, so every co-signer can check it without trusting the operator |
| D7 | **Echo before signing.** A co-signer answers round two only after `n-t+1` participants have echoed the same `(label, message hash)` | constraint 5. Any two sets of `n-t+1` share at least `t` members when `n ≥ 3t-2`, and at least one of them is honest. At `n = 3t-2` this is `2t-1`, the quorum that has to be online anyway |
| D8 | **Testnet3 first, regtest in tests, mainnet refused.** Simple taproot channels are mandatory. Swap-in is off | `Lsp.kt` knows only mainnet and testnet3. Iceberg cannot sign an anchor-outputs (ECDSA) channel at all. Phoenix's swap-in is a 2-of-2 with the LSP using a single user key |

### Who can do what, once every phase is in

| holder | can | cannot |
|---|---|---|
| the operator | run the node, create invoices, choose when to send the authorised payments, and see everything. It can grief: send `error` so the LSP force-closes, at the cost of fees | sign a commitment, close, splice or send an unauthorised payment. It cannot keep any of the group's balance from a force close (D5) |
| any member (all hold the node seed, D4) | impersonate the node to the LSP. That is the same griefing, and the same cost | move channel funds, or sweep a closing output |
| `t - 1` members, colluding | refuse to sign, which stops the wallet if fewer than `2t-1` honest co-signers stay online | sign anything. Learn a share, provided the echo rule and the label ledger hold |
| a quorum (`2t-1` co-signers online, `t` approvers) | everything the group can do | - |
| a removed member | everything they could do before: they keep a share and the seed, and Iceberg has no resharing | nothing new. Phase 14 is how the group gets them out |
| the LSP | what any Lightning counterparty can do | tell that a group is behind the channel |
| a relay | see that the room has traffic | read any of it. Every kind here is an MLS inner event and never goes on a relay in the clear |

In-flight HTLCs are the one balance still keyed from the node seed. Their HTLC
basepoint stays a node key, because claiming an HTLC on chain is time-critical,
and a FROST session lasting minutes or days is the wrong tool for it. The
statement caps how much can be in flight (Phase 6), and that cap is the most the
node seed can ever be worth.

---

## The operator's life cycle

```
  nominate ─► FROST session (t approvals) ─► operator statement, term N
                                                  │
   every co-signer: answer only term N's operator ◄┤
                                                  │
   previous operator's device: stop the Peer,   ◄─┤
   send "stopped" with its last commitment numbers
                                                  │
   new operator's device: start the Peer from   ◄─┘
   the wallet's seed, restore from peer storage,
   reconnect, send its first snapshot
```

The fence does not depend on the old device behaving. Once term `N` exists,
co-signers refuse every request from anyone else, so an old node that keeps
running cannot sign a commitment, close or force-close. All it can do is make the
LSP force-close, and D5 sends that money to the group. A clean handover is
quicker and saves fees, but safety does not depend on it.

---

## Kinds

These are proposed, and taken from 30333 upward. 30330-30332 are the highest kinds
in use. Every one is an MLS inner event, and none is ever broadcast. Check again
before Phase 5. As the batch-signing note says, a new family should not rely on a
routing accident to keep it apart from the others.

| kind | from | carries | phase |
|---|---|---|---|
| 30333 | proposer | Iceberg ceremony proposal: `t`, the participants | 5 |
| 30334 | participant | DKG round 1: commitments to seed contributions | 5 |
| 30335 | participant | DKG round 2: seed contributions, each encrypted to its recipient | 5 |
| 30336 | participant | DKG round 3: public share and a transcript signature | 5 |
| 30337 | anyone | ceremony failure | 5 |
| 30338 | group (FROST) | Lightning wallet statement | 6 |
| 30339 | group (FROST) | operator statement | 7 |
| 30340 | opener or operator | node seed, encrypted to each participant | 6 |
| 30341 | operator | signing request: label, transaction, context | 9 |
| 30342 | participant | echo: `(label, message hash)` | 9 |
| 30343 | participant | round-one contributions, in batches of labels | 9 |
| 30344 | participant | round-two signature share | 9 |
| 30345 | operator | wallet snapshot | 10 |
| 30346 | group (FROST) | authorisation: pay, close, force-close or splice-out | 12, 13 |
| 30347 | member | invoice request | 11 |
| 30348 | operator | invoice | 11 |
| 30349 | old operator | stopped, with its last commitment numbers | 7 |

---

## Phase 0 — decisions and two spikes

**About three days. No product code.**

1. **Confirm the LSP signs simple taproot channels on testnet3.** Connect
   lightning-kmp's `jvmTest` `Node` program to the testnet3 LSP in `Lsp.kt`, with
   `SimpleTaprootChannels` set to mandatory, and open a channel by on-the-fly
   funding. If the LSP will not, nothing in this plan works there. The fallback is
   regtest only, against the docker eclair in `lightning-kmp/testing/`, until it
   will.
2. **Measure the latency budget.** In the same harness, use a `FundingSigner`
   that sleeps before each answer, and grow the sleep until the LSP gives up. The
   question is how long it waits between its `commit_sig` and our
   `revoke_and_ack` and `commit_sig`. Measure it twice: first with pings answered
   (after Phase 2), then with them not answered (as the code is today). That number
   decides whether a phone quorum is workable, and it goes in Phase 9's timeouts.
   If it is under a minute, say so on the ceremony screen: the wallet needs devices
   that stay on.
3. **Reserve the kinds above.**
4. **Shares and seed at rest.** Same as the onchain wallet: shares are plaintext
   in Room, and the cost is stated where the group opens the wallet. The node seed
   is stored beside the share, with the same sentence. Moving both into the
   platform keystore is its own piece of work and does not gate this one.
5. **Where library work lands.** The DKG goes in the bitcoin-kmp fork, beside
   `Iceberg.kt`. Seam changes go in the lightning-kmp fork's `threshold` branch.
   The app moves its submodule pointer once per library phase, as
   `e0d2b82` ("Advance the lightning-kmp submodule") did.

---

## Phase 1 — an Iceberg DKG, in bitcoin-kmp

**About a week, plus review. The newest cryptography in the plan.**

`fr.acinq.bitcoin.crypto.iceberg.IcebergDkg`, in `commonMain` beside `Iceberg`. It
is pure functions over stored inputs, shaped like `ChillDKG`, so the app's ritual
can be resumed from whatever messages it has persisted.

```
participants 1..n, ordered by host key. For every (t-1)-subset T, in rank order:
  holders(T) = { j : j ∉ T }                      n-t+1 of them, so at least t

round 1  each j, for each T with j ∈ holders(T):
           c[j,T] = random 32 bytes
           publish  h[j,T] = H("bennu/iceberg-dkg/commit/v1", sid, T, j, c[j,T])
round 2  once every round-1 message is in:
           send c[j,T] to every other holder of T, encrypted to that holder
round 3  each k assembles, for each T with k ∈ holders(T):
           check every c[j,T] against h[j,T]
           s[T] = H("bennu/iceberg-dkg/seed/v1", sid, T, c[j,T] for j in holders(T))
         share = version | n | t | k | s[T] in rank order  ->  Iceberg share_parse
         publish pubshare_k and a signature over the transcript hash
done     groupPublicKey over all n public shares, which checks they lie on one
         polynomial of degree t-1. Every transcript signature covers the same
         hash, which includes the group key
```

Why it is shaped this way:

- **Each seed is random if any one of its holders is honest.** There are at least
  `t` holders and at most `t-1` corrupt members, so every seed has an honest
  holder.
- **Each seed is secret from the `t-1` members of its subset.** Its
  contributions are sent only to its holders.
- **Commit, then reveal, so nobody can choose last.** Without round 1, the last
  contributor could try values until it got a key it liked.
- **Equivocation is caught, not blamed.** A contributor who sends two holders
  different `c` gives them different seeds. Their public shares then fail
  `groupPublicKey`'s consistency check. As with ChillDKG, the response is one
  failure path: the key is unusable and the session dies. Naming the culprit
  takes a complaint round, and v1 does not have one.
- **The transcript signature is the CertEq step.** It stops a coordinator from
  showing different members different key sets.
- **Repair comes free, and is v1's recovery.** Every seed a member holds is also
  held by `n-t` other participants. A member who lost their device can be sent
  their seeds again by the others. The repaired share must reproduce their
  published public share, which checks what they were sent. This replaces
  ChillDKG's `recoveryData`.

**Tests,** in `commonTest`:

- 2-of-4, 3-of-7 and 4-of-10. Every participant's share parses. The group key
  agrees over every `(2t-1)`-subset of public shares. One signature from each such
  subset verifies against a stock MuSig2 cosigner, using the flow in
  `IcebergTestsCommon.kt`.
- **The layout is pinned.** For a fixed set of contributions, check the serialised
  share bytes against literals, so a change to the order of `rss.h` fails a test
  rather than a payment.
- **Negatives.** A wrong reveal against its commitment. One contributor
  equivocating, which must fail `groupPublicKey`. A reordered participant list,
  which must produce a different `sid`, so no key. Non-deployable `(n, t)`, which
  must be refused.
- **Repair.** Drop a share, rebuild it from the other holders' seeds, and check
  that the public share matches.

**Gate:** someone outside the project reviews it before Phase 15 lifts anything on
mainnet. The note in `iceberg.md` applies here with more force.

---

## Phase 2 — the signer waits, in lightning-kmp

**About a week, in the fork's `threshold` branch.**

1. **Suspend the seam.** Every `FundingSigner` method becomes `suspend`. The call
   sites the parity plan routed in its Phase 2 (13 files) should all be reached
   from `ChannelState.process`, which already suspends. Confirming that is the
   first task, because any site reached from a non-suspending helper has to be
   lifted with it. `PrivateKeyFundingSigner` and
   the in-process `IcebergFundingSigner` stay call-for-call the same.
2. **Keep-alive leaves the channel loop.** `Peer` answers `Ping` with `Pong` in
   its own coroutine, before the message reaches the channel `when`. A channel
   waiting on its group must not cost the connection.
3. **A signature can time out.** Each seam call has a deadline (from Phase 0) and
   fails with `Either.Left`. The channel then disconnects instead of erroring, so
   `channel_reestablish` retransmits later. A timed-out label is *burned*: it is
   never used again.
4. **Labels the group can pre-provision.** Verification nonces already have a
   deterministic label, `verificationSessionId(fundingTxId, remoteFundingPubKey,
   commitIndex)`. Fresh-nonce and published-nonce sessions use `randomBytes32()`
   today. They change to `H("bennu/iceberg/fresh/v1", fundingTxId, counter)`, with
   a counter the signer allocates and persists, strictly increasing per channel.
   Every label the node will need is then computable ahead of time. That lets
   co-signers publish their round-one contributions in advance (Phase 9), and
   takes round one off the payment's critical path.
5. **`RemoteIcebergFundingSigner`,** a `FundingSigner` that takes a transport
   interface (`requestRoundTwo(label, message, context): Either<…>`) and a pool of
   round-one contributions. lightning-kmp knows nothing about nostr. The app
   supplies the transport.

**Tests:** run the channel suite (`IcebergChannelTestsCommon`) with a remote
signer that adds random delays of 0 to 5 seconds and drops one answer in ten.
Every case still passes. Two checks are new: no label ever signs two different
messages, and a ping arriving during a 5-second signature gets an answer.

---

## Phase 3 — the money in a closing channel belongs to the group, in lightning-kmp

**About a week and a half, in the fork.**

1. **Basepoint injection, built like the funding seam.** `ChannelKeys` gains
   `withGroupBasepoints(payment: PublicKey, delayedPayment: PublicKey)`. Once those
   are set, `paymentKey` and `delayedPaymentKey` become poison pills, as
   `fundingKey` is. Grep the 8 call sites in `commonMain` and route each one, with
   the same exit criterion the parity plan used: any private-key use left behind is
   a path that throws at runtime.
2. **No sweeping by the node.** For a group-keyed output, lightning-kmp builds no
   `ClaimRemoteDelayedOutputTx` or main-output claim of its own. Instead it emits a
   `ChannelAction` naming the output, its script leaf, its tweak (the
   per-commitment point for to_local) and the block it unlocks at. The app turns
   that into a FROST spend (Phase 13).
3. **Anchors.** If our anchor is keyed from the delayed or payment basepoint, as
   simple taproot channels appear to do, then the grep in step 1 will find it.
   Spending that anchor to bump a force close's fee would need a FROST signature.
   lightning-kmp's anchor CPFP is therefore off for group channels. A force close
   pays its fee from the commitment, and a slow confirmation is accepted.
4. **A final script from outside.** The closing script is the node's
   `defaultFinalScriptPubKey`. It comes from a provider the app supplies, which is
   the onchain wallet's next receive address, instead of from
   `finalOnChainWallet`'s P2WPKH key. lightning-kmp's own `FinalWallet` is not
   started for a group node.
5. **One channel per group node.** `channelKeyPath(fundingPubKey)` is a function
   of the funding key, and a group's funding key is the same in every channel. Two
   channels would then share a `shaSeed`. Refuse a second channel with a test. A
   key per channel is a follow-up, if a group ever needs two LSPs.

The basepoints come from the onchain wallet's ChillDKG key, `K`.
`GroupLightningDerivation` has the same shape as `GroupWalletDerivation`'s account
step, with its own tag, so it is a different key that every member can recompute:

```
I   = HMAC-SHA512("bennu/group-lightning/basepoints/v1", serP(K) ‖ chainHash ‖ ser32(wallet))
K_b = K + I[0:32]·G,  c_b = I[32:64]
payment basepoint  = CKDpub((K_b, c_b), 0)
delayed basepoint  = CKDpub((K_b, c_b), 1)
```

Spending them takes a FROST tapscript signature. For to_remote, the leaf key is
the payment basepoint. For to_local, it is the delayed basepoint plus
`SHA256(per_commitment_point ‖ basepoint)·G`. Neither has a TapTweak, because
these are script-path keys. Both fit `TweakCache` as plain tweaks.

**Tests:** a pinned vector for the derivation. Then, in the channel suite, a
remote force close and a local force close on a group channel. In both, the output
that pays us must spend under a FROST script-path signature from a simulated
ChillDKG quorum (`correctlySpends`), and must not spend under any node-seed key.
The mutual close must pay the provided script.

---

## Phase 4 — a test network for the group's wallets, in the app

**About three days. It unblocks everything after it, because D8 puts the first
real runs on testnet3.**

`NodeParamsManager.chain` is hardcoded `Chain.Mainnet`, and every wallet screen
reads it. The onchain wallet's statement and spend code already accept any chain
by name.

- **The room's wallets choose a chain.** `GroupWalletRoute` and the wallet row
  take the chain from the statement being shown. *Open a group wallet* offers
  testnet3 while `GroupLightningRollout.ENABLED` is on.
- **A testnet3 Electrum client per identity.** It is started on first use, with a
  server from `AppConfigurationManager.randomElectrumServer`. It is the same kind
  of client the onchain wallet note's Phase 4 left as a follow-up for nsec
  profiles.
- **`GroupOnchainWallets` is keyed by `(chain, account)`.**

**Test:** on regtest, a group opens a wallet, receives into it and spends from it,
with mainnet's wallet and statement unaffected.

---

## Phase 5 — the Iceberg ceremony, in the app

**About a week.**

`IcebergRitualManager`, modelled on `ChillDkgRitualManager` and following its
rules. The protocol messages above go out as MLS inner events in the group's room.
Each device stores *inputs*, not state, so a ceremony survives the app being
killed. Members are ordered by host key, sorted bytewise, and a test pins that
order. Faults are values, and their single failure path names the culprit.

- **Who can open one.** Only a participant of the room's onchain wallet key (D1),
  and only once a verified onchain wallet statement exists on the chosen chain
  (D2). Otherwise the room shows *Open a group onchain wallet first* where the
  Lightning row would be.
- **Choosing `t`.** Only deployable values are offered for the room's `n`, using
  constraint 3. If no value fits, the reason is stated: *A Lightning wallet needs
  at least four members who can sign.*
- **Approval gates,** as in the ChillDKG ceremony. Joining is one gate, and sending
  contributions is another. The transcript signature is a third, with the same
  reason: it is the check that stops a coordinator from swapping in a key nobody
  contributed to.
- **Storage:** an `IcebergKeySession` table holds `t`, `n`, the participants, the
  group key, the public shares, the serialised **share** and the stored round
  inputs. The schema goes from 26 to 27 with an `AutoMigration`. The transcript
  gets lines worded by what each step does, as the ChillDKG lines are.
- **Encrypting contributions.** MLS encrypts to the whole room, but each round-2
  value must reach only its holders. Each one is therefore NIP-44 encrypted to the
  recipient's ceremony host key inside the inner event.

**Tests:** the two-device JVM harness used by `SignedGroupKeyStateTest`, extended
to four devices and four databases. A 2-of-4 ceremony completes on every device
with the same group key. One device killed and restarted between rounds still
completes. A device on an old build ignores the new kinds.

---

## Phase 6 — the group opens its Lightning wallet

**About three days.**

`GroupLightningWalletStatement`, kind **30338**, is FROST-signed through
`proposeSigning`. It is never broadcast. `BroadcastGroupSignedEventScreen` refuses
it, and a test checks that refusal. `ProposedEvent.summarize` reads *Open a
Lightning wallet on testnet3*.

| tag | carries |
|---|---|
| `d` | `<chain>:<wallet>`, so the newest statement replaces older ones per wallet |
| `chain`, `wallet` | which wallet |
| `onchain` | the onchain `(chain, account)` it closes to. It must already be verified |
| `iceberg` | the ceremony's group key, `n`, `t`, and the participants in order |
| `node` | the node id the wallet's seed produces |
| `lsp` | the LSP node id |
| `policy` | in-flight cap, commitment fee allowance, maximum liquidity fee |

A reader accepts the statement only if all of these hold. The room signed it. The
onchain statement it names verifies. The Iceberg key matches the reader's own
`IcebergKeySession`. The node id is the one the seed it holds produces. A reader
who is not an Iceberg participant checks everything except the last two.

**The node seed.** The member who proposes the statement generates 32 random
bytes. They send them, kind **30340**, NIP-44 encrypted to each participant inside
the room, once the statement completes. The seed is stored beside the Iceberg
share. A participant who joins the ceremony later, or whose device was restored,
asks the operator for it, and the reply is checked against the statement's `node`
tag.

**`GroupLightningWallet`,** a read model over `GroupSignedEvent`, built like
`GroupWallet.of`. Like the onchain wallet, it has no table.

---

## Phase 7 — electing an operator, and handing over

**About a week.**

**The operator statement,** kind **30339**, is FROST-signed and never broadcast.

| tag | carries |
|---|---|
| `d` | `<chain>:<wallet>` |
| `operator` | the member's nostr key |
| `term` | the previous term plus one |
| `previous` | the operator it replaces, if any |

- **Nominating yourself** is a button on the Lightning wallet screen, *Run this
  wallet on my device*, which proposes a statement naming the device's owner. It
  goes through the existing proposal list and approval screen, and needs `t`
  approvals, the same as any group decision. You cannot nominate someone else.
- **The current operator** is the verified statement with the highest term. If
  two sessions complete with the same term, the lower event id wins, so every
  device decides the same way. The loser's device stops as an old operator would.
- **Stepping down** is a statement naming nobody. The wallet then stops until
  someone is elected.

**Handover, on each device,** when a newer term arrives:

| device | does |
|---|---|
| every co-signer | refuses any request with an older term, or from any sender other than the new operator (constraint 2: the MLS sender is the identity checked) |
| the old operator | stops its `Peer`, sends **30349** with its last commitment numbers, and deletes its channel database once the new operator's first snapshot shows the channel open. Until then it keeps the database, read-only, never started |
| the new operator | starts the node from the wallet's seed (Phase 8). Its channels come back from the LSP's peer storage. It reconnects and sends a snapshot |

**The start gate.** A device starts the group node only if the newest operator
statement it holds names its owner, *and* the room has synced within the last few
minutes. A phone that comes back after a week offline catches up on the room
before its node can start. The co-signers are the real fence. The gate saves an
old node from becoming the griefing case.

**The new node is behind.** If the LSP's backup is newer than what the new node
restored, lightning-kmp's data-loss protection waits for the LSP to force-close.
That costs fees and the channel, but D5 means the money arrives under the group's
key. The 30349 commitment numbers and the co-signers' ledgers (Phase 9) let the
screen say what happened.

---

## Phase 8 — the operator's node

**About a week and a half.**

`GroupLightningNode`, in `composeApp/…/compose/lightning/`, runs only on the
current operator's device, one per wallet:

- **Its own `Peer`, apart from the identity's Phoenix node.** It has its own
  `NodeParams`: the statement's chain, `SimpleTaprootChannels` mandatory, swap-in
  off, the LSP from the statement, and zero-conf with the LSP only. Its channel
  and payment databases are keyed by node id, the way `DatabaseManager` keys
  Phoenix's. It shares the chain's Electrum client from Phase 4. The first task is
  to find out whether `lightning-kmp-app/library` can host a second
  `PhoenixBusiness` for a seed it did not create. If not, write a slim builder over
  lightning-kmp's own `Peer`, without the Phoenix managers.
- **The key manager** is `LocalKeyManager(seed)`, wrapped so that `channelKeys`
  applies `withFundingSigner(RemoteIcebergFundingSigner)` and
  `withGroupBasepoints`, as the fork's `SignerInjectingKeyManager` does in tests.
  The final script provider is the onchain wallet's next receive address.
- **The transport** for the remote signer sends 30341 and collects 30344 from the
  room. It keeps the round-one pools from 30343, and asks for more when a pool
  falls below a few commitments' worth.
- **On Android, a foreground service** with a notification keeps the node
  running while the device is the operator. iOS cannot keep it running in the
  background, so the nomination screen says an iPhone makes a poor operator.

**Test:** regtest end to end in `jvmTest`, against the docker eclair. Four
simulated devices form a 2-of-4 group, and one of them operates. A channel opens
by on-the-fly funding, a payment arrives, and the channel survives the operator
restarting.

---

## Phase 9 — the co-signer on every participant's device

**About two weeks. This is the phase with the security argument in it.**

`IcebergCoSigner` runs one per identity, beside `GroupTransactionWatcher`. It is
started whether or not anybody opens the wallet, because the group's payments
depend on it.

**Round one, ahead of time.** When the operator asks, or when the pool shown in a
snapshot runs low, the co-signer publishes 30343. That is a batch of nonce
contributions for the next labels of each channel. It covers the verification
labels for commit indices `N…N+W` and the fresh labels for counters `C…C+W`. A
contribution depends only on the share and the label, so publishing it early
gives nothing away.

**Round two, per request.** When a 30341 arrives, the co-signer runs these
checks. If any fails, it refuses, and the reason goes in the log:

1. **The sender is the current operator**, the request's term is the current
   term, and the wallet statement verifies.
2. **The label is fresh.** It has the form its kind requires: a recomputed
   verification label, or a fresh label with a counter above the highest this
   co-signer has answered for the channel. This co-signer has never answered it.
3. **The funding output.** The transaction spends the channel's funding
   outpoint, recorded when the co-signer first signed for the channel. Its script
   is the MuSig2 taproot of the group key and the LSP's funding key, and
   `keyAggregationCheck` passes on the request's cache.
4. **The kind of transaction.** It is classified from its outputs as the LSP's
   commitment, our own commitment, a mutual close or a splice. The request has to
   be the kind it claims to be.
5. **The LSP's commitment.** Our main output pays the group payment basepoint
   (Phase 3), which the co-signer derives itself. Its amount is at least the last
   amount this co-signer signed, minus the open authorisations' budgets (amount
   plus fee cap), minus the commitment fee allowance. The commitment number is
   one more than the last. HTLC outputs come with their leaf scripts, and each one
   is checked against its output key. Every outgoing HTLC's payment hash is in an
   open authorisation. The total in flight is under the statement's cap.
6. **Our own commitment** (force close) requires a group-signed force-close
   authorisation (30346).
7. **A mutual close.** Our output pays an onchain wallet address the co-signer
   derives itself, and its amount is our balance minus a capped fee.
8. **A splice.** The new funding output is again the group key with the LSP's.
   Spliced-out outputs go only to addresses in a splice-out authorisation.

Once the checks pass, the co-signer **echoes**: it sends 30342 with the label and
the message hash, and records both in its ledger before sending. It answers round
two (30344) only once `n-t+1` participants, counting itself, have echoed the same
hash for that label. It signs with the `2t-1` contributions the request names, and
accepts each one only from the MLS sender that its index names. If it sees two
hashes echoed for one label, it never answers that label. Then it raises the
alarm in the transcript, because that is a coordinator equivocating.

**Approving without a person.** Checks 5 to 8 refer only to commitments the
co-signer has already seen and to authorisations the group has signed. So a
co-signer never shows a commitment to its owner. A person only sees a refusal,
as a transcript line.

**The ledger,** schema 27 to 28, is never pruned:

```kotlin
@Entity data class IcebergLabel(@PrimaryKey val label: HexKey, val wallet: String, val messageHash: HexKey, val echoedAt: Long, val signedAt: Long?)
@Entity data class IcebergChannelRecord(@PrimaryKey val channelId: HexKey, val wallet: String, val fundingOutPoint: String,
                                        val lastCommitmentNumber: Long, val lastOurBalanceMsat: Long, val lastFreshCounter: Long)
```

**A restored device is the dangerous one.** `iceberg.md`: *"restoring a backup
taken before it was written is the one restore that is dangerous."* A co-signer
whose share was repaired (Phase 1), or whose database is older than its share,
does not answer anything until it has collected high-water marks from `t` other
participants. Those are the highest commitment number and fresh counter each has
answered, per channel. After that it answers only above the highest of them.

**Tests,** in the four-device harness, a hand-built 30341 for each numbered check,
each asserting that no 30344 was published. Two more: an operator that sends two
messages under one label never gets a share from anyone, and a co-signer with
a restored database answers nothing until it has the marks.

---

## Phase 10 — sharing the wallet's data

**About a week.**

The operator sends **30345** whenever something changes, debounced, and every few
minutes while the node runs:

| field | |
|---|---|
| `term`, `nodeId`, `sentAt` | whose snapshot this is, and how old |
| balance | spendable, and in flight |
| channels | id, state, capacity, our balance, inbound liquidity, funding outpoint, commitment number |
| payments | the newest page: hash, direction, amount, fees, status, time, description |
| closing | outputs waiting on the group (Phase 13) |
| pools | round-one contributions left, per channel |

Members store the newest snapshot and a `GroupLightningPayment` row per payment.
The schema goes from 28 to 29.

**A snapshot is the operator's claim,** and the screen says *as reported by Thandi,
2 minutes ago*. A co-signer can check one number for itself: our balance against
the last commitment it signed. The screen then says either *matches the last
commitment you signed* or *differs from the last commitment you signed*. That
difference is the operator lying, or the snapshot being out of date, and the
co-signer cannot tell which.

**The screen,** `GroupLightningWalletRoute`, is pushed with `NavigateBackButton`.
It shows the balance, the operator and when it was last seen, the channels, the
payments, and the actions: *Receive*, *Send*, and *Run this wallet on my device*.
It has four states, through `ScreenStateTransition`, and its `EmptyState` has its
own message. A *Lightning wallet* row sits beside the *Group wallet* row on
`ChatRoomDetailScreen`. The conventions in `CLAUDE.md` apply as they did to the
onchain wallet screens: amounts as format arguments, QR modules
`m3-color-exempt`, and `m3Audit` before committing.

---

## Phase 11 — receive

**About four days.**

- **An invoice for an amount.** A member sends 30347 with the amount and a
  description. The operator's node creates a bolt11 and replies with 30348. The
  member's screen shows it as a QR code, and checks that its payee is the
  statement's node id. If there is no operator, or no reply within a minute, the
  screen says the wallet cannot receive right now, and why.
- **A standing Bolt12 offer** for the node, carried in the snapshot. It works
  whenever the operator is online.
- **Liquidity.** The first payment opens the channel by the LSP's on-the-fly
  funding, and so do later payments the channel cannot hold. The co-signer's
  check 5 allows the LSP's fee to come out of the incoming amount, up to the
  statement's maximum liquidity fee. The receive screen shows the fee before the
  invoice, as Phoenix does.

Receiving needs `2t-1` co-signers too. While they are not online, an incoming
HTLC waits, and eventually the payer's side fails it.

---

## Phase 12 — send

**About a week.**

- **An authorisation, kind 30346 `pay`,** proposed with `proposeSigning`. It
  carries the invoice or offer, the amount, a fee cap, an expiry, and the payment
  hash. `summarize` reads *Pay 12,000 sats to …*. Any member can propose one, and
  `t` approve it, like any other group decision.
- **Once it completes,** the operator's node pays it, through the LSP as
  trampoline. Each co-signer then has a budget of `amount + fee cap` against that
  payment hash, until the payment settles, fails or expires.
- **The operator cannot add a payment.** An HTLC whose hash is not in an
  authorisation fails check 5 on every co-signer, so its commitment is never
  signed.

An allowance comes later, when wanted: a statement field that lets the operator
send up to an amount per day without an authorisation. It is off in v1. That
choice belongs to the group, not to this plan.

---

## Phase 13 — closing, sweeping and splicing out

**About a week and a half.**

- **Close:** a 30346 `close` authorisation, then a mutual close to the onchain
  wallet. Check 7 holds on each co-signer.
- **Force close:** a 30346 `force-close` authorisation, needed when the LSP has
  gone away. Co-signers then sign our own commitment.
- **Sweeping into the onchain wallet.** The `ChannelAction`s from Phase 3 become
  rows that `GroupTransactionWatcher` follows. Once an output's CSV has expired, any
  member can propose a sweep. That is a FROST spend whose input is script path.
  `GroupSpend.check` gains a script-path input type, refused for anything except
  the two leaves Phase 3 defines, derived by the member. The output must go to an
  onchain wallet address. That rule is new to the onchain wallet's checks, and its
  tests go beside the seven it already has.
- **Splice out** to an onchain wallet address: a 30346 `splice-out`
  authorisation. It is design B from the parity plan, which serves the same group
  key at every funding index.
- **Splice in from the onchain wallet** is left for its own plan. It needs the
  group's FROST-signed inputs inside lightning-kmp's interactive transaction, so
  two threshold protocols have to finish within one channel negotiation. Until
  then, money reaches the Lightning wallet by being paid to it.

---

## Phase 14 — members change, and the key does not

**About a week.**

Iceberg has no resharing. When a member is removed, they keep a share and the
seed. When a member is added, they cannot sign. Either way the fix is the one the
onchain wallet note gives for its own key, applied to the Lightning wallet:

1. Run a new Iceberg ceremony over the new membership (Phase 5).
2. Open a new wallet statement with a new seed, using the next `wallet` number.
3. Close the old wallet's channel to the onchain wallet, signed by the old
   co-signers. Fund the new one by paying it.

The member-removal flow says so in a sentence when the room has a Lightning
wallet, just as it already does for the onchain wallet. A group that cannot gather
the old wallet's quorum can still close: the LSP can force-close, and D5 sends
the funds to the group's onchain key. That key has the same problem. If it has
been rotated as well, sweep before you rotate.

Moving a channel to a new funding key with a splice, instead of closing it, is a
follow-up. It would turn the parity plan's rejected "fresh group per index" into
a "key epoch per funding index".

---

## Phase 15 — rollout

| gate | lifts when |
|---|---|
| `GroupLightningRollout.ENABLED`, off | Phases 0 to 12 are in, the JVM suite passes, and a regtest end-to-end run passes |
| testnet3 only. Mainnet refused in code, not just hidden | the Iceberg module and the Phase 1 DKG have been reviewed outside the project, *and* a testnet3 group has run for a month without a refused label or a lost channel |
| on mainnet, if ever: a cap on channel capacity per wallet | the same release criterion as the onchain wallet's `MAINNET_CAP` |

Every member's device needs a build that understands the new kinds before the
group opens a Lightning wallet. A member on an old build is a co-signer who never
answers. Unlike an onchain spend, where a missing member just means a slower
quorum, here that is a smaller group, and with it a wallet that is more often down.
The ceremony screen lists anyone whose device has not answered the proposal.

---

## Order, and what can run in parallel

| phase | depends on | lands in |
|---|---|---|
| 0 | - | nowhere |
| 1 DKG | 0 | bitcoin-kmp |
| 2 async seam | 0 | lightning-kmp |
| 3 group basepoints | 2 | lightning-kmp |
| 4 test network | - | app |
| 5 ceremony | 1, 4 | app |
| 6 statement | 3, 5 | app |
| 7 election | 6 | app |
| 8 operator node | 2, 3, 7 | app |
| 9 co-signer | 2, 8 | app |
| 10 snapshots | 8 | app |
| 11 receive | 9, 10 | app |
| 12 send | 9, 10 | app |
| 13 close and sweep | 3, 9 | app and the onchain wallet |
| 14 re-key | 13 | app |
| 15 rollout | all | app |

Phases 1, 2 and 4 are independent of each other and can run in parallel. The
first milestone a person can see is the end of Phase 11: a 2-of-4 group on
testnet3 receiving a payment.

| phase | estimate |
|---|---|
| 0 | 3 days |
| 1 | 1 week + external review |
| 2 | 1 week |
| 3 | 1.5 weeks |
| 4 | 3 days |
| 5 | 1 week |
| 6 | 3 days |
| 7 | 1 week |
| 8 | 1.5 weeks |
| 9 | 2 weeks |
| 10 | 1 week |
| 11 | 4 days |
| 12 | 1 week |
| 13 | 1.5 weeks |
| 14 | 1 week |
| 15 | 1 day |
| **total** | **about 3½ to 4 months, one engineer** |

---

## Risks and open questions

| risk | why it matters | what to do |
|---|---|---|
| the LSP waits less than a phone quorum takes | every payment times out, and the wallet works only between always-on devices | Phase 0 measures it before any app code. A desktop co-signer (the JVM target) is the realistic third device for a 2-of-4 group |
| the DKG is wrong | a key leaks, or a share cannot sign | Phase 1's tests, then review outside the project before mainnet |
| label reuse | a share leaks, with no error | deterministic labels from Phase 2, the echo rule and the ledger from Phase 9, and a burned label on every timeout |
| background limits on phones | co-signers are asleep, so payments fail | a foreground service on Android. Say on the nomination and ceremony screens that iOS devices are rarely online |
| two operators run the same node | a stale state reaches the LSP | co-signers fence on term (Phase 7). D5 caps the damage at fees and a channel |
| a removed member keeps the seed | griefing, plus in-flight HTLCs up to the cap | Phase 14's re-key, and the in-flight cap in the statement |
| `n ≤ 10` and `n ≥ 3t-2` | small groups cannot have a Lightning wallet | stated on the ceremony screen. This is the scheme's limit, not the app's |
| lightning-kmp moves upstream | the fork's seam changes conflict | keep fork changes behind the two seams (`FundingSigner`, basepoints), as the parity plan did |

**Three decisions belong to the group or the product, not to this plan:**

1. **Is the node seed shared (D4) or held by the operator only?** Held by the
   operator only, every handover becomes a close and a reopen, and losing the
   operator's device means waiting for the LSP to force-close. Shared, every
   participant can grief. D5 makes sharing it safe for the group's balance, which
   is why it is recommended.
2. **Does every outgoing payment need `t` approvals,** or should the group give
   its operator an allowance? Phase 12 builds the first and leaves room for the
   second.
3. **Iceberg, or the fork's other signer?** The lightning-kmp fork also carries
   **prefractal**, a nested FROST+MuSig2 signer that can express any `t`-of-`n`.
   It may be able to reuse the group's existing ChillDKG shares, which would remove
   Phases 1, 5 and 14's re-key. Its round one needs only `t` members online, not
   `2t-1`. It derives its nonces from labels the same way Iceberg does, so the
   label rule and the echo step still apply. Against it: round two must be signed
   by exactly the set that answered round one, so one member dropping out between
   the two rounds costs the whole attempt. Check whether it can reuse the ChillDKG
   shares before Phase 1 starts, because if it can, it is the cheaper plan. This
   plan follows the request and uses Iceberg.

---

## Appendix — what was considered and rejected

**The creator deals the shares.** `Iceberg.dealShares` works today and would
save Phase 1. But for the length of that call, one device holds the group's whole
key, which is exactly what a threshold scheme is meant to prevent.

**A fresh seed per operator.** Each operator would have their own node, so a
removed member would get nothing from an old seed. The cost is that every
handover becomes a close and a reopen, with two onchain fees and a liquidity fee,
and a lost operator phone means a force close. D4 together with D5 gets most of
the safety for none of that cost.

**Leaving the basepoints on the node seed.** It is simpler, and it is what the
fork's port does. It also means the operator can take the group's balance with one
`error` message. See constraint 7.

**The operator's personal Phoenix node holds the channel.** That needs no fork
work, but it is one person's money with extra steps.

**Echo with `2t-1` instead of `n-t+1`.** The two are equal at `n = 3t-2`. Above
that, `2t-1` sets can be disjoint enough for an equivocating coordinator to get
two hashes past two quorums. `n-t+1` is the count whose sets always overlap in an
honest member.

**Random fresh-nonce labels, as the fork has them.** They are safe in one
process. Over a network they cannot be pre-provisioned, so every payment would add
round one to its critical path. A random label also gives the ledger nothing to
order, so the check that the counter goes up has nothing to compare.
