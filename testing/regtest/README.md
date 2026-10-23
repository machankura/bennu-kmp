# Regtest network for the group Lightning wallet

`regtest.sh up` builds it from nothing: bitcoind 31.1, electrs, an eclair LSP configured as a Phoenix
LSP (`lsp.conf`), and a second eclair that pays the group and is paid by it (`payer.conf`), with a
channel from the payer to the LSP. Every port is on localhost: bitcoind RPC 28443, electrs 28001, the
LSP 29735 and its API 28080, the payer's API 28081.

```bash
./regtest.sh up
```

```bash
BENNU_REGTEST=1 ../../gradlew -p ../.. :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

```bash
./regtest.sh down
```

`./regtest.sh mine 6`, `./regtest.sh btc <rpc>`, `./regtest.sh lsp <method> -d k=v` and
`./regtest.sh payer <method> -d k=v` reach each piece by hand. What the run does, and what each LSP
setting is for, is in `docs/group-lightning-wallet.md`, "The regtest run".
