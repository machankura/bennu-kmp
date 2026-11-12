# Bennu

Bennu is a group chat app where the group holds bitcoin together. Groups talk over
[nostr](https://github.com/nostr-protocol/nips) using the Marmot protocol's MLS encryption. A group can also run a key ceremony that gives it a threshold key, which no
single member holds. The group uses that key to sign as one and to keep on-chain and Lightning
wallets that need a quorum of members to spend.

It is a Kotlin Multiplatform app with a Compose Multiplatform UI, built for Android, iOS and
desktop (JVM).

## What it does

- **Group chat.** End-to-end encrypted groups on Marmot, with direct messages between members,
  inside a group or one to one.
- **A key for the group.** A ChillDKG ceremony, run in the chat, gives the group a threshold key.
  Members then approve and sign the group's events with FROST, several at a time if needed.
- **An on-chain group wallet.** A taproot wallet held by the group's key. Every member who signs
  a spend checks it first.
- **Subgroups and guardians.** A group can create subgroups. A parent can keep a guardian leaf in
  a subgroup's wallet, or in a wallet it keeps for one member, and take the coins back after a
  delay the parent chooses.
- **A group Lightning wallet.** Channels whose funding key is an Iceberg threshold key. One
  elected operator's node runs them, and co-signers check every payment, close and splice. The
  group can authorise buying inbound liquidity from its LSP.
- **A wallet of your own.** Each profile can have a personal Lightning and on-chain wallet, built
  on [lightning-kmp-app](https://github.com/kngako/lightning-kmp-app), a fork of the shared code
  from ACINQ's Phoenix.
- **Profiles.** Create a profile, sign in with an existing nsec (without a wallet), preview one
  from an npub, or keep several profiles on one device. A profile can reach the network over Tor
  and use its own Electrum server.

The reasoning behind each of these, including the protocol details and the failure modes, is in
[docs/](docs/README.md).

## Building

You need JDK 21 and the Android SDK. iOS builds also need Xcode on macOS.

Clone with submodules. The app builds against `lightning-kmp-app` as a composite build, and that
repository nests its own builds down to `secp256k1-kmp`:

```bash
git clone --recursive https://code.sigidli.com/machankura/bennu-kmp.git
```

In an existing clone, or in a git worktree, which does not get submodules automatically:

```bash
git submodule update --init --recursive
```

Each of the five nested Gradle builds needs a `local.properties` that points at the Android SDK.
The file is gitignored, so create it in each one:

```bash
for d in . lightning-kmp-app \
         lightning-kmp-app/experimental/lightning-kmp \
         lightning-kmp-app/experimental/lightning-kmp/experimental/bitcoin-kmp \
         lightning-kmp-app/experimental/lightning-kmp/experimental/bitcoin-kmp/experimental/secp256k1-kmp; do
  echo "sdk.dir=$HOME/Android/Sdk" > "$d/local.properties"
done
```

Then build each target (on Windows, use `gradlew.bat`):

- **Android:** `./gradlew :composeApp:assembleDebug`
- **Desktop:** `./gradlew :composeApp:run`
- **iOS:** open [iosApp](./iosApp) in Xcode and run it from there.

[docs/jvm-target.md](docs/jvm-target.md) explains what the desktop target needed and how it is
packaged.

## Checking your work

```bash
./gradlew :composeApp:compileDebugKotlinAndroid :composeApp:jvmTest
```

```bash
./gradlew check
```

`check` also runs the Material Design 3 audit, `docs/scripts/m3-audit.sh`. It fails a build that
adds hardcoded spacing or colours, title-case strings, or touch targets under 48dp. [CLAUDE.md](CLAUDE.md)
lists the UI conventions the audit enforces.

The group Lightning wallet also has an end-to-end test against a regtest network with a real
LSP. It is skipped unless asked for; [testing/regtest/README.md](testing/regtest/README.md)
explains how to run it.

## Layout

- [composeApp](./composeApp/src) is the shared app. `commonMain` holds most of the code, and
  `androidMain`, `iosMain` and `jvmMain` hold each platform's own parts.
- [iosApp](./iosApp) is the iOS entry point.
- [lightning-kmp-app](https://github.com/kngako/lightning-kmp-app) is the wallet library, a
  submodule.
- [docs](docs/README.md) holds the design notes.
- [testing/regtest](testing/regtest/README.md) holds the regtest network.

## License

Copyright (C) 2026 Machankura

This program is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
[GNU General Public License](LICENSE) for more details.
