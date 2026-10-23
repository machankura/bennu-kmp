#!/usr/bin/env bash
# The group Lightning wallet's regtest network. `up` builds it from nothing: bitcoind with three
# wallets (a miner, the LSP's, the payer's), electrs, both eclair nodes, and a channel from the payer to
# the LSP. `down` removes all of it.
set -euo pipefail
cd "$(dirname "$0")"

btc() { docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=foo -rpcpassword=bar "$@"; }
lsp() { local m=$1; shift; curl -sf -u :lsp -X POST "http://127.0.0.1:28080/$m" "$@"; }
payer() { local m=$1; shift; curl -sf -u :payer -X POST "http://127.0.0.1:28081/$m" "$@"; }
mine() { btc -rpcwallet=miner generatetoaddress "${1:-1}" "$(btc -rpcwallet=miner getnewaddress)" >/dev/null; }
wait_for() { for _ in $(seq 1 90); do if eval "$1" >/dev/null 2>&1; then return 0; fi; sleep 1; done; echo "timed out waiting for: $1" >&2; return 1; }

case "${1:-}" in
  up)
    docker compose up -d bitcoind
    wait_for "btc getblockchaininfo"
    for w in miner lsp payer; do btc -named createwallet wallet_name=$w load_on_startup=true >/dev/null 2>&1 || btc loadwallet $w >/dev/null 2>&1 || true; done
    mine 150
    btc -rpcwallet=miner sendtoaddress "$(btc -rpcwallet=lsp getnewaddress)" 10 >/dev/null
    btc -rpcwallet=miner sendtoaddress "$(btc -rpcwallet=payer getnewaddress)" 10 >/dev/null
    mine 6
    docker compose up -d electrs lsp payer
    wait_for "lsp getinfo"
    wait_for "payer getinfo"
    lspId=$(lsp getinfo | python3 -c 'import sys,json; print(json.load(sys.stdin)["nodeId"])')
    payer connect -d uri="$lspId@lsp:9735" >/dev/null
    payer open -d nodeId="$lspId" -d fundingSatoshis=5000000 >/dev/null
    # The funding transaction reaches the mempool a moment after the open is answered.
    wait_for "[ \$(btc getmempoolinfo | python3 -c 'import sys,json; print(json.load(sys.stdin)[\"size\"])') -gt 0 ]"
    wait_for "mine 1; payer channels | grep -q NORMAL"
    echo "LSP $lspId on 127.0.0.1:29735, electrs on 127.0.0.1:28001"
    ;;
  down) docker compose down -v ;;
  mine) mine "${2:-1}" ;;
  btc) shift; btc "$@" ;;
  lsp) shift; lsp "$@"; echo ;;
  payer) shift; payer "$@"; echo ;;
  *) echo "usage: $0 up | down | mine [n] | btc <args> | lsp <method> [-d k=v] | payer <method> [-d k=v]"; exit 1 ;;
esac
