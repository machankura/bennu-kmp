# Deriving keys from a group's shared key

`SharedKeyDerivation` turns a group's ChillDKG threshold key into further keys the
group can sign with, at paths that look like BIP32 but deliberately are not.

## What it produces

```kotlin
val derived = SharedKeyDerivation.derive(thresholdPublicKey)   // default m/9420/0/0
derived.publicKey   // XonlyPublicKey — 32 bytes, the form nostr and Marmot use
derived.cache       // TweakCache — required to sign
derived.hex         // publicKey as hex
```

**The cache is not an optimisation.** A FROST signing session has to be created
with a cache carrying the same tweaks, or the partial signatures aggregate to
something that verifies against a different key. Code that takes only
`publicKey` and later tries to sign will fail in a way that is tedious to diagnose
from the outside, because the signature is valid — just not for the key you
expected.

Everything is a pure function of the threshold key and the path, so every member's
device computes the same result with no agreement round and nothing to store.
Rederive rather than persist.

## Why not BIP32

The paths read like BIP32 and are walked the same way, index by index. They are
not BIP32, and the difference matters.

**A BIP32 node is a key *and* a chain code. ChillDKG produces no chain code.**
`ParticipantFinalizeResult` gives you `thresholdPublicKey`, `secretShare`,
`publicShares` and `recovery` — no chain code, because ChillDKG is not a BIP32
ceremony.

**Hardened derivation is impossible here, not merely unimplemented.** It is:

```
I = HMAC-SHA512(c_par, 0x00 || ser256(k_par) || ser32(i))
```

which takes the parent *private* key. In a FROST group nobody holds that; it
exists only as shares. No member, and no quorum of members short of reconstructing
the secret, can perform it. So `m/44'/1237'/0'/0/0` — the NIP-06 nostr path — is
not derivable from a threshold key by anyone.

**Non-hardened derivation is available, as an additive tweak.**

```
t = HMAC-SHA512(c_par, serP(K_par) || ser32(i))[0:32]
K' = K + t·G
```

which is exactly what `TweakCache.tweak` does. But note where the chain code
appears: only in *computing* `t`. A FROST tweak takes `t` as an input, so
**choosing the scalar directly removes the chain code from the problem entirely.**

That is what this does:

```
t = SHA256("mantra/shared-key/tweak/v1" || parentXonlyKey || index-as-4-bytes)
```

Each scalar commits to the key being tweaked as well as the index, so steps cannot
be reordered or replayed at a different depth to reach the same key.
`listOf(0L)` and `listOf(0L, 0L, 0L)` do not collide — there is a test for it.

The `mantra/` prefix is deliberate and did not change when the app was rebranded to
Curated. The string is an input to the hash, so renaming it derives different keys
from the same threshold key — orphaning every room already created, and splitting
Curated off from any Mantra peer in a shared group. A rename is a protocol fork and
would need a `v2` tag, not an edit to this one.

### What avoiding BIP32 also avoids

With x-only keys there is no single obvious `serP(K_par)`: BIP32 serialises
compressed 33-byte keys, BIP340 uses 32-byte x-only, and the parity byte has to
come from somewhere. Two devices picking different conventions would **silently
derive different keys** rather than fail. Choosing the tweak input ourselves makes
the domain separation explicit and removes that class of bug.

Nothing is lost in exchange. No external tool can derive these children anyway —
none of them has the chain code, and nostr has no way to publish one. An npub is
bare bech32 over a 32-byte key with no chain code, depth or parent fingerprint;
NIP-06 uses BIP32 internally but discards everything except the leaf public key.

## The security property this inherits

Additive tweaking is what non-hardened BIP32 does, and it carries the same
weakness. Because `t` is publicly computable:

```
k' = k + t     ⟹     k = k' − t
```

**Anyone who learns one derived private key recovers the group's threshold key**
and can sign as the group with no quorum at all — defeating the entire point of
the ceremony. In ordinary BIP32 this is why BIP44 hardens the first three levels:
a leaked leaf costs you one account, not the wallet. That defence is unavailable
here.

The mitigating factor is that a derived private key does not normally exist:
reconstructing one needs `t` members to collude, at which point they already have
the parent. So the rule is narrow and absolute:

> **Never reconstruct a derived key in the clear.** Any code path that could — an
> export, a "reveal private key" screen, a test helper, a debugging convenience —
> leaks the group key, not just the key it appears to expose.

If you need many keys that cannot be linked back to one another, derivation is the
wrong tool: run a ceremony per key. Each output is then independent and no single
leak reaches the others.

## Paths

`derive` and `marmotGroupId` both take `path: List<Long>`, defaulting to
`MARMOT_ADMIN_GROUP_PATH` (`m/9420/0/0`). Any depth works.

`9420` is arbitrary and has to stay put: the derived key *is* the admin room's
id, so changing the path orphans every room already created — members would derive
a different id and stop finding the room at all.

There is no string-path parser for input. Paths are written as lists at the call
site. If one is added it must reject `'` outright rather than accepting a hardened
path it cannot honour.

## Recording the path

MIP-01's group data is a fixed TLS schema — version, `nostrGroupId`, name,
description, `adminPubkeys`, relays, four image fields, `disappearingMessageSecs`.
There is no extension map, and inventing a field would emit bytes other Marmot
clients cannot decode.

So the path rides in the description, which is the only free text MIP-01 offers:

```
Admins of Ubuntu Collective.

Shared key path: m/9420/0/0
```

`formatPath`, `parsePath` and `describe` round-trip this. The marker sits on its
own line and `parsePath` scans lines for it, so somebody rewriting the rest of the
description does not cost the group the record of how its key was derived.

Worth storing even though the path is currently a constant: it is what rebuilds
the `TweakCache` a signing session needs, and recomputing from the constant only
holds while the constant never changes. A room that records the path it was made
under lets a later scheme coexist with rooms already created.

`parsePath` refuses hardened indices — `m/9420'/0/0` returns null. A hardened path
cannot have been walked here, so acting on one would derive something other than
what the room claims.

Consequence worth knowing: the path is visible to anyone in the group, in any
Marmot client, since description is user-facing text. The path is not a secret and
the key it derives from is not published, but the room does announce how it was
made.
