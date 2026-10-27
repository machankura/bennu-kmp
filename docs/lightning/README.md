# lightning docs

Remediation plans for the findings of the October 2026 review of the group
Lightning wallet — the review asked whether the wallet's operator can spend
lightning funds without `t - 1` other key share holders, and found the core
claim intact with seven exceptions. Each finding has its own phased plan,
written against the code it changes. The parent design doc is
[group-lightning-wallet.md](../group-lightning-wallet.md); read its
constraints, decision table and trust table before any of these.

| document | finding | severity | lands in |
|---|---|---|---|
| [force-close-revoked-commitment.md](./force-close-revoked-commitment.md) | the force-close fence at `GroupLightningCoSigner.kt:345` admits an already-revoked commitment: the operator can turn a routine force-close authorisation into the LSP's penalty taking the whole channel | **critical** — total channel loss, no LSP collusion needed | app, then the fork |
| [node-seed-in-flight-exposure.md](./node-seed-in-flight-exposure.md) | in-flight *incoming* HTLCs are claimable by any seed holder (every participant, every ex-operator, every removed member) to an address of their choosing; the statement's in-flight cap covers only offered HTLCs, so the real bound is the channel cap | **high** — theft, bounded by 1M sats a channel | app; elimination is a spike |
| [first-commitment-policy.md](./first-commitment-policy.md) | the first commitment a device signs skips rule 2, the in-flight cap and the floor (`GroupLightningCoSigner.kt:399`), and the floor-start then defends the outcome | medium — needs a colluding LSP | app only |
| [mutual-close-amount.md](./mutual-close-amount.md) | `checkClosing` prices the group's output off the operator's spec, not the device's own ledger; an understated spec disables the check | medium — needs a colluding LSP | app only |
| [restored-device-marks.md](./restored-device-marks.md) | a restored co-signer has an empty label ledger *and silently regains every spent budget*; the planned high-water-mark collection and the tested-but-unwired share repair do not exist | medium — bounded by the echo rule, compounds the other `pinned == null` holes | app; new kinds 30367-30370, schema 33 |
| [griefing-surface.md](./griefing-surface.md) | four liveness holes: one member burning pool labels, the operator burning budgets at echo time and wedging a channel, a ceremony killed by a non-participant, one bad share wedging a request unattributed | low — no theft, but the trust table overpromises | app only |
| [signer-hygiene.md](./signer-hygiene.md) | five small items: a failure broadcast that can carry decrypted contribution bytes, the label assignment persisted after hand-out against its contract, `fullySignedCommitTx`'s `.right!!` crashing the Peer's run loop, two missing negative tests, the unmeasured 60-second signing deadline | low, except the crash path | app and the fork |

## The order to build them in

The plans were written together and cross-reference each other; the order
below is the one that keeps the co-signer consistent at every step.

1. **The cheap correctness fixes first.** `signer-hygiene.md` Phases 1, 2 and
   4 (failure-broadcast vocabulary, eager label reservation, the two negative
   tests) and `griefing-surface.md` Phase 1 (the ceremony-failure participant
   check). No wire or schema changes; each is a day or two.
2. **The co-signer core, in one release.** `force-close-revoked-commitment.md`
   Phases 1-2, `first-commitment-policy.md`, `mutual-close-amount.md` Phases
   1-2, and `griefing-surface.md` Phases 2-4 all rewrite parts of
   `GroupLightningCoSigner` / `CoSignerPolicy` and two of them change what the
   channel ledger's `commitIndex` means (`griefing-surface.md` Phase 4 is
   explicit that it must land together with the force-close fence). They share
   schema 33 and one test-suite pass. The fork half of the force-close plan
   (Phase 2, holding `revoke_and_ack` until the group signature completes)
   makes the signing deadline load-bearing for revocation too, so
   `signer-hygiene.md` Phase 5's measurement becomes blocking here.
3. **Recovery.** `restored-device-marks.md` — its marks gate is the recovery
   path the Phase-2 refusals above rely on (`pinned == null` becomes a refusal
   in three of them), so it can follow them but the group is incomplete until
   it lands. New kinds 30367-30370; every device needs the build before a
   group relies on marks.
4. **The exposure narrative.** `node-seed-in-flight-exposure.md` Phases 1-2
   (doc correction, on-screen exposure, the incoming-side cap — which depends
   on `first-commitment-policy.md` having landed), then its Phase 3 spike
   decides whether group-keyed HTLC claims are ever built.
5. **The fork's crash path and the measurement.** `signer-hygiene.md` Phases
   3 and 5, on the `claude/group-lightning-wallet` branch.

## Two things every plan assumes

- **The operator is hostile.** No fix may rest on the operator's node
  behaving; each plan says which device enforces its rule, and it is never the
  operator's.
- **A group is only as covered as its slowest device.** Co-signer rules are
  per-device: a refusal rule protects the wallet once at most `n - t` devices
  still run the old build, because every echo quorum of `n - t + 1` then
  contains an upgraded device that refuses. Until then, mixed builds fail
  safe (refusals stall, they do not sign). Each plan states its own rollout
  moment.
