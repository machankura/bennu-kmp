# An Electrum client for a profile with no node

How an nsec or npub profile comes to watch the chain: the group wallets it can
already sign for, the member wallets it can already hold, and the spends the group
has already signed. Today none of them can, because the only Electrum client in
the app belongs to the Lightning node, and only a mnemonic profile starts one.

Read [nsec-sign-in.md](./nsec-sign-in.md)'s *The constraint* for why an nsec has
no node, and [group-chain-wallet.md](./group-chain-wallet.md)'s *An identity
without a node*, which named this plan as its follow-up. Neither changes:
an nsec still cannot have a Lightning wallet. This plan gives it eyes on the chain, not a
wallet of its own.

**Planned**, not built. Written against `3aba04a0`, with the
`lightning-kmp-app` submodule at `e0d2b82`. Four phases, one commit each, and a
fifth that is a device check rather than code.

The headline: **every consumer already takes an `IElectrumClient`, and every
"cannot watch" branch in the app is `wallets == null`. So the work is a connection
loop, about sixty lines ported from the node's daemon, and one `remember` in
`BennuNavHost` that hands it to whoever has no node. No screen learns a new state.
Five of them lose one.**

---

## What is already there

| fact | where | what it means here |
|---|---|---|
| `GroupOnchainWallets`, `GroupOnchainWallet` and `GroupTransactionWatcher` take an `IElectrumClient`, not a `PhoenixBusiness` | [wallet/](../composeApp/src/commonMain/kotlin/com/machankura/bennu/compose/wallet/) | nothing below the nav host knows where the client came from |
| both are made from `activeIdentity?.business?.electrumClient` and are null without it | [BennuNavHost.kt:246](../composeApp/src/commonMain/kotlin/com/machankura/bennu/compose/ui/composable/navigation/BennuNavHost.kt) | the one place the null enters |
| every `canWatch` is `wallets != null`, and `CannotWatch` / `CANNOT_WATCH` are the same test | `GroupWalletViewModel:124`, `MemberWalletViewModel:71`, `GroupWalletAddressesViewModel:65`, `BennuNavHost:1222` | with a client for everyone, the condition never holds |
| an nsec or npub identity's preferences already carry Tor and a custom Electrum server, *"saved anyway"* | [IdentityWriter.kt:64](../composeApp/src/commonMain/kotlin/com/machankura/bennu/compose/identity/IdentityWriter.kt) `prepareIdentity` | the configuration exists and nothing reads it yet |
| the server lists are public top-level vals, and `platformElectrumRegtestConf` is a public `expect fun` | `lightning-kmp-app/.../data/ElectrumServers.kt` | a random server can be picked without an `AppConfigurationManager`, which needs a business |
| `AppConnectionsDaemon` is the only reconnect loop, and its constructor takes a `PhoenixBusiness` | `lightning-kmp-app/.../managers/AppConnectionsDaemon.kt:241` | it cannot be reused. Its Electrum branch is what gets ported |
| `ElectrumClient.connect` returns after one attempt; reconnecting is the caller's job | `lightning-kmp/.../ElectrumClient.kt:75` | the loop is not optional |
| `ElectrumClient.stop()` is final: *"the client cannot be used after it has been closed"* | `ElectrumClient.kt:296` | one client per identity, made anew on a switch |
| `NetworkMonitor` lives on `PhoenixGlobal`, not the business, and `start()` is idempotent on every platform | `PhoenixGlobal.kt:38`, the three `NetworkMonitor.*.kt` | available without a node, and safe to start from a second place |
| `FakeElectrum` implements `IElectrumClient` for the wallet tests | [FakeElectrum.kt](../composeApp/src/jvmTest/kotlin/com/machankura/bennu/compose/wallet/FakeElectrum.kt) | the downstream tests need nothing new |

## Decisions

**D1. Only a profile with no node gets one.** A mnemonic profile keeps the node's
client. A second connection from the same device would tell a second server the
same addresses, and would give the group wallets a view of the chain that can
disagree with the node's. The rule is `identity.business?.electrumClient ?:
standalone`, tested on `business`, not on the kind, as `switchToIdentity` does.

**D2. It lives in `composeApp`, not the submodule.** The loop is small, and the
submodule's managers are all shaped around a business. Picking a random server is
a `when (chain)` over the public lists. That copies five lines of
`AppConfigurationManager.randomElectrumServer`, which is cheaper than a submodule
commit to move them. `Testnet4` and `Signet` are `TODO()` there, and they are
"no server" here, never a crash. A profile on those networks reads as "connecting",
the same as a node with no server configured.

**D3. The identity's own preferences choose the server**, exactly as
`BusinessManager` does for a node: `userPrefs.getElectrumServer` if set, a random
server for `NodeParamsManager.chain` if not, and a reconnect whenever either
preference changes. The same profile, given a seed later, keeps the same server.

**D4. Tor behaves as it does for the node, and that is not much.** This fork's
socket builder is plain TCP. `isTorEnabled` only swaps the random list for onion
addresses, which plain TCP cannot reach, and `requireOnionIfTorEnabled` refuses a
clearnet custom server. The standalone client copies both rules, so a profile
with Tor on never falls back to a clearnet connection against the user's choice.
Its wallets then stay on "connecting", which is what a mnemonic profile with Tor
on already shows today. A real Tor transport is its own plan; see
[Out of scope](#out-of-scope).

**D5. It connects when the identity becomes active, not when a wallet screen
opens.** `GroupTransactionWatcher` sends the group's signed spends and follows
them to a confirmation *"whether or not anybody opens the wallet"*. A lazy
client would make that true only for mnemonic profiles. A bare connection reveals
the device's IP and nothing else. Addresses are still subscribed only when a
wallet is first asked for, as they are now. **This is a behaviour change:** an
nsec or npub profile made no Electrum connection before, and from Phase 2 it
makes one on every start. The note's rollout section must say so.

**D6. An npub watches too.** Watching needs a descriptor, and descriptors come
from group-signed events, which a read-only profile can already read. Rebroadcasting
a transaction somebody else signed needs no key. Every send stays gated on
`canSign` and the share, as now, so an npub sees balances and proposes nothing.

**D7. Foreground only.** The client lives in `applicationIOScope`, as
`GroupOnchainWallets` does. A mnemonic node is also run headless by Android's
WorkManager, for payments. A nodeless profile has no payment to wake up for, and
a watch that only runs in the background would answer a question nobody is asking.

---

## Phase 1 — the connection loop

**Half a day, most of it in the test.**

`wallet/StandaloneElectrum.kt`: owns one `ElectrumClient`, and exposes it as
`client: IElectrumClient`, with `start()` and `stop()`. Inside is the daemon's
Electrum branch with the business taken out:

- collects `networkMonitor.networkState`, `userPrefs.getIsTorEnabled` and
  `userPrefs.getElectrumServer`, combined; any change disconnects and starts the
  loop again, as `forceDisconnect` does;
- on `Connection.CLOSED`, waits `connectionPause(attempt)`, the same table, then
  calls `connect` with the same handshake-timeout ladder;
- `stop()` cancels the loop and calls `ElectrumClient.stop()`. A stopped instance
  is never restarted: the client's own comment says it cannot be, so a switch
  makes a new one.

The connect itself goes behind a small seam: a
`suspend (ServerAddress, Duration) -> Unit` defaulting to `client.connect(…,
TcpSocket.Builder(), …)`. The loop can then be tested against a status flow the
test drives, with no socket.

**Test** in `jvmTest`, `StandaloneElectrumJvmTest`, on a `TestScope`:
- a custom server is used when set, and a random server from the chain's list when not;
- a closed connection is retried after the pause, and the attempt count resets on
  `ESTABLISHED`;
- losing the network disconnects, and getting it back connects;
- Tor on with a clearnet custom server that requires onion never calls `connect`;
- after `stop()`, nothing is retried and the client is stopped.

## Phase 2 — one client per identity, handed to the wallets

**Half a day.**

In `BennuNavHost`, the two `remember(activeIdentityForWallets?.business)` blocks
become one resolution keyed on the identity:

```kotlin
val electrum: IElectrumClient? = remember(activeIdentityForWallets?.id, activeIdentityForWallets?.business) {
    val identity = activeIdentityForWallets ?: return@remember null
    identity.business?.electrumClient
        ?: StandaloneElectrum(phoenixGlobal.networkMonitor, identity.userPrefs, NodeParamsManager.chain, applicationIOScope)
            .also { it.start() }
            .client
}
```

The standalone instance is disposed with the identity, the same as
`GroupOnchainWallets` (stopped in `onDispose`). `GroupOnchainWallets` and
`GroupTransactionWatcher` are then made from `electrum`, not from
`business.electrumClient`. Keyed on `id` *and* `business`, because a switch between two
nsec profiles changes the first and not the second, and the old profile's client
must not survive it (the reason `GroupOnchainWallets`' own comment gives).

The resolution is written as a plain function beside `StandaloneElectrum`, so it
can be tested without composition, and `BennuNavHost` calls it.

**Test**: a mnemonic identity resolves to the business's client and makes no
standalone; an nsec identity and an npub identity each get one; switching from
one nsec to another stops the first before the second connects.

After this phase every nodeless profile can watch, and the "cannot watch"
branches are unreachable but still compiled.

## Phase 3 — the branches that can no longer be taken

**Half a day, all of it deletion.**

`wallets` becomes non-null wherever it is passed: `GroupWalletViewModel`,
`GroupSendViewModel`, `GroupWalletAddressesViewModel`, `MemberWalletViewModel`,
`MemberWalletsViewModel`, `MemberSendViewModel`, and the `ActiveProfileRoute` at
`BennuNavHost:929`. The compiler then finds every `wallets == null` for us.
Removed with them:

- `GroupWalletAddressesUIState.CannotWatch` and its `EmptyState`;
- `MemberWalletManager.Refusal.CANNOT_WATCH` and `NewMemberWalletViewModel`'s
  `canWatch` parameter. The new-member-wallet entrance opens for nsec profiles
  (D3 of [member-guarded-wallets.md](./member-guarded-wallets.md) said why it was
  closed, and that reason is gone). An npub still cannot ask, because asking is
  signed;
- `canWatch` on `GroupWalletUIState.Loaded` and `MemberWalletUIState.Loaded`.
  `walletState == null` then means one thing, "before Electrum's first answer",
  and the KDoc that explained the two meanings goes;
- the `GroupWalletScreen` send, spend and fund gates lose their `&& canWatch`;
- the strings `this_profile_cannot_watch_the_chain` and
  `this_profile_cannot_watch_its_own_coins`.

The tests that drove these with `wallets = null` are rewritten against
`FakeElectrum`, or deleted where the only thing they checked was the refusal.

`m3-audit.sh --check` should not move: no new strings, no new colours, one
`EmptyState` fewer.

## Phase 4 — the notes

**An hour.**

- [nsec-sign-in.md](./nsec-sign-in.md): the *Out of scope* bullet keeps "a wallet
  for an nsec identity", and gains a line that watching shipped here.
- [group-chain-wallet.md](./group-chain-wallet.md): *An identity without a node*
  and the follow-up at the end point here and say it is built. Its
  `MAX_ADDRESS_INDEX` and absolute fee-ceiling rows stay as they are. Both are
  still right for a member whose client has not answered yet, and a member with a
  client gets Electrum's fee *suggestion* through `suggestedFeeratePerVbyte`
  without any change.
- [member-guarded-wallets.md](./member-guarded-wallets.md): D3 and the refusal
  table's *no Electrum client* row.
- This note: **Planned** becomes **Built**, with a table of where the build chose
  differently, as the other built plans have.

## Phase 5 — on a device

Process, not code. On testnet, with an nsec profile in a group that has a wallet,
on Android and on desktop:

1. the group wallet shows a balance and a receive address, not "cannot watch";
2. funding that address moves it to the next one;
3. a spend this profile co-signs is broadcast from this device and shows as
   confirmed without the proposer's device being online;
4. switching to a mnemonic profile and back leaves one Electrum connection open,
   not two (the server's logs, or `lsof` on desktop);
5. with Tor on and no custom server, no clearnet connection is made.

---

## Out of scope

- **A Lightning wallet for an nsec profile.** Unchanged from
  [nsec-sign-in.md](./nsec-sign-in.md): it needs a seed, which is a second secret.
- **A Tor transport.** The fork has none for the node either (D4). When one
  exists it is a `TcpSocket.Builder`, and Phase 1's connect seam is where it goes.
- **One client for every profile, mnemonic included.** This is what starting the node
  lazily would make possible, and that is already named as a follow-on in
  nsec-sign-in.md. Phase 2's resolution is the line that would change.
- **Saying "can't reach Electrum" rather than loading forever.** True for
  mnemonic profiles today as well. `IElectrumClient.connectionStatus` is on the
  interface, so it is a screen change for every profile at once, and its own plan.

## Checking the work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew :composeApp:m3Audit
```
