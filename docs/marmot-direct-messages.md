# Direct messages inside a Marmot group

A one-to-one message carried as an MLS application message: an ordinary NIP-59 gift
wrap, ephemeral-keyed and signed the way NIP-59 says, addressed to one member and
never broadcast to a relay. Every member of the group sees that a private message
was sent and to whom. Only the recipient can read it.

Read [Why a throwaway key, and what it costs](#why-a-throwaway-key-and-what-it-costs)
before changing anything here. Two consequences of that choice reach into every
part of this, and one of them cannot be undone.

## What a direct message is, outside in

```
kind:445 GroupEvent            ephemeral signer, h-tag = nostrGroupId   → relays
└─ ChaCha20-Poly1305           MLS exporter secret                      → group
   └─ MLS PrivateMessage       ContentType.APPLICATION                  → group
      │                        sender authenticated by MLS leaf ────────┐
      └─ kind:1059 wrap        pubkey = throwaway, signed by it         │ → group
         └─ NIP-44             conversation key: throwaway ↔ recipient  │
            └─ kind:13 seal    signed by the sender ───── bound to ─────┘
               └─ NIP-44                                                  → recipient
                  └─ kind:14 rumor                                        → recipient
```

The group reaches the fourth layer and stops. Everything above it is what a
bystander renders from: a recipient, a time, a wall — and a sender who comes from
the MLS frame rather than from anything written in the wrap.

The two outer layers are unchanged. A direct message is an ordinary kind:445 to
anyone watching a relay and an ordinary application message to `MlsGroup`; the
whole feature is what goes in as the application payload.

`MarmotDirectMessage` builds and opens it, as pure functions of their arguments
with no database and no MLS state — which is what makes it testable, since
Room-backed code cannot be unit-tested in this project.

## Why a throwaway key, and what it costs

The wrap uses a fresh random key and is signed by it, exactly as NIP-59 specifies
and exactly as `GiftWrapEvent.create` builds one. Nothing in the wrap names the
sender.

**What it does not buy: hiding the sender from the group.** MLS authenticates every
application message to a leaf. `mlsGroup.memberIdentityHex(decrypted.senderLeafIndex)`
yields the sender's real pubkey no matter what the inner event claims, and there is
no way to send an application message that does not. The throwaway key removes a
*redundant* copy of an identity the MLS frame already proves — it does not remove
the identity.

**What it does buy:** a wrap lifted out of its MLS frame — a log line, a database
export, a crash dump — is not attributable to anyone. It is also a well-formed
NIP-59 gift wrap rather than a Marmot-shaped variant, which is worth something to
anyone reading the payload with ordinary nostr tooling.

Two costs follow, and both are load-bearing.

### It requires a carve-out in a security check

`MarmotInboundManager.processPrivateMessage` rejects any inner application event
whose `pubKey` is not the MLS sender's credential identity:

```
MIP-03: inner event pubkey (…) does not match MLS sender identity (…)
```

That is MIP-03, not local policy, and it exists so a member cannot mint events
claiming a different author. A throwaway-keyed wrap cannot satisfy a check about
its author, so kind:1059 is exempt from it:

```kotlin
if (innerEvent.kind != GiftWrapEvent.KIND && innerEvent.pubKey != senderIdentity) {
    return GroupEventResult.Error(groupId, "MIP-03: inner event pubkey …")
}
```

`senderIdentity` is now *required* rather than merely compared. Under the old check
a null identity failed only because the comparison failed; attribution depends on
it outright, so it fails on its own.

**The check is not weakened, it is relocated and strengthened.** What replaces it
for kind:1059 is `seal.pubKey == senderIdentity` on a seal whose `verify()` passes
— a signature bound to an MLS leaf, rather than a plaintext field compared to one.
The attack it stops is a member re-wrapping a seal they were legitimately sent and
passing it to a third party as its author's; that fails because the MLS frame says
who actually sent *this* one. Forging one outright needs the other member's private
key.

A seal is harder to relabel than it looks, and not because of the signature.
NIP-44 derives the conversation key from the pubkey being claimed, so writing
another member's key over a seal makes it undecryptable by the person it was
encrypted for — it fails at the decryption, before any check runs, and comes out as
a message the recipient simply cannot read. The label is bound to the key rather
than asserted alongside it. `verify()` is what catches the remaining case: a seal
altered after signing in a way that survives decryption. Both have tests, because
the first was asserted the wrong way round until one of them failed.

> **Compatibility.** Every member's client needs this carve-out. A Marmot client
> implementing MIP-03 as written drops these messages as impersonation — silently,
> as a `GroupEventResult.Error` — so a group with one unpatched member has one
> member who never receives a direct message and is never told why. This is a
> divergence from the spec, and a reason to raise it upstream rather than carry it
> indefinitely.

### The envelope, in current-profile rooms

The carve-out is now confined to legacy rooms, and the upstream answer turned out
to be a shape rather than a rule change. A current-profile room ([marmot-v2.md](./marmot-v2.md),
Phase 2) carries the wrap *inside* an unsigned Marmot app event of
`MarmotDirectMessage.ENVELOPE_KIND` (30330), whose `pubkey` is the sender's account,
whose one tag is `["p", recipient]` and whose `content` is the wrap's JSON. The
envelope passes the author check like any other payload, so there is nothing to
exempt, and `mip03Rejection` takes a `legacyRoom` flag: in a current-profile room a
bare kind:1059 is refused as what every other conformant client refuses it as.

The properties above survive intact. The envelope is unsigned — a `MarmotAppEvent`
has no signature to give — so a lifted envelope is a *claim* of authorship and not a
proof; the wrap inside is still signed by nobody; the seal-to-leaf binding in
`classify` still decides who wrote the words. What a bystander sees is unchanged:
that a private message was sent, and to whom.

An old build renders the envelope as a raw-JSON bubble, which is why a legacy room
keeps sending the bare wrap: a current-profile room admits only leaves carrying an
identity proof, and only a build past Phase 3 can make one, so nobody in one runs
an old build. The room's profile is the flag.

### The sender cannot read their own messages back

The throwaway private key is discarded at send time, so nothing can reopen the wrap
afterwards — not even the person who built it. NIP-17 solves this by sending a
second wrap addressed to yourself; here that would be a second application message,
and so a second *"sent a private message"* line in everyone else's transcript, so it
is not available.

What follows:

- The sending device keeps its own copy, because `sendChatMessage` writes the
  plaintext `ChatMessage` row on the way out. Normal use is unaffected.
- A second device, or a reinstall, gets **nothing** — the sender's own half of
  every conversation is unreadable to them anywhere it was not typed. The
  recipient's half is unaffected.
- On a re-sync the sender's own message comes back as an opaque wrap they cannot
  open, indistinguishable from a bystander's view. `ChatMessage.directMessage`
  guards that case explicitly and files nothing, because a bystander line would
  replace the words on the row `sendChatMessage` wrote — the only copy there is.
  `NostrDao.persistInboundChatMessage` carries the same guard, for the same reason,
  on the NIP-17 path.

Do not solve this by storing the throwaway private key. A per-message private key at
rest is strictly worse than the identity-keyed wrap it was chosen over, and it
reintroduces the attribution the throwaway key exists to remove. There is a test —
`the sender cannot reopen their own message` — whose only job is to make that
reversal fail loudly rather than ship.

## The one way to broadcast this by accident

Nothing sweeps events to relays on its own. Broadcast is driven by
`BroadcastNostrEventRequest` rows joined to `NostrEvent`, inserted explicitly. So
the requirement "this must never be broadcast" reduces to one query:

```sql
SELECT * FROM GiftWrapPayload WHERE publicKey = :publicKey AND giftWrapSealId IS NULL
```

`GiftWrapPayloadDao.observeUnsealedGiftWrapPayloads`. The notary watches it, and
`DatabaseChatRepository.sealGiftWrapPayload` seals every row it returns and hands it
to `NostrNip17Dao.persistAndBroadcastGiftWrap` — which inserts a
`BroadcastNostrEventRequest` per relay. **Write one `GiftWrapPayload` row authored
by the local user with a null seal id and the direct message leaves the device.**

Hence the rule:

> A Marmot direct message never writes to `GiftWrapPayload` or `GiftWrapMessage`
> at all. Its outbound queue is `MarmotInnerEvent`, and so is its inbound record.

The risk is sharper here than it would be with a Marmot-shaped payload, because what
this builds is a genuine, well-formed, correctly signed NIP-59 gift wrap. It is
indistinguishable from something the NIP-17 path would be right to publish. Nothing
but the tables it is kept out of stops it going to a relay.

`GiftWrapMessage` could not be written anyway without a `NostrEvent` row — its
foreign key — and `NostrEvent` is the broadcast join target. The rule is also
enforced at the other end: `sealGiftWrapPayload` refuses any payload whose room has
a non-null `mlsGroupState`, and logs. An invariant in code is what stops a later
refactor from walking a direct message onto a relay without reading this page first.

**The Welcome is exempt, and the exemption is not optional.** MIP-02 addresses
kind:444 to someone who is not yet in the group and holds no key to read a
kind:445 — a relay-borne gift wrap is the only way to reach them, and
`MarmotOutboundDao.deliveryWelcome` writes exactly such a `GiftWrapPayload` row on
purpose. Every Welcome carries its own room's `nostrGroupId`, so a refusal keyed on
`mlsGroupState` alone matches all of them: while it did, no invite to any Marmot
room was ever delivered, and — since the unsealed queue is a single-row flow — the
first refused Welcome sat at its head and blocked every payload queued behind it.
The check is therefore on kind first, room second. When adding a kind to that
exemption, the question to answer is whether its recipient can read a kind:445; if
they can, it does not belong on this path.

## Attribution comes from MLS, not from the payload

Because nothing in the wrap names the sender, every sender-derived field is read
from the MLS frame. This is not a workaround; it is the correct source, and it is
what makes the carve-out above safe.

`GroupEventResult.ApplicationMessage` carries `senderLeafIndex` but not the resolved
identity, and `ChatMessage.fromGroupEventResult` has no `MlsGroup` to resolve it
with. The obvious fix — adding `senderIdentity` to the result — is not available:
quartz is a **binary dependency** here (`com.vitorpamplona.quartz:quartz:1.14.0`),
and the local checkout at `~/Documents/development/nostr/amethyst/quartz` is a
reference copy, not a build input. Changing the result type would mean publishing a
fork.

So `NostrDao` resolves it at the call site instead and passes it in. It holds the
group, the leaf index is already on the result, and an application message advances
no epoch, so the tree has not moved by the time it reads it. Same value, no fork.

This also settled a bug that predates the feature. All fourteen arms of
`fromGroupEventResult` computed:

```kotlin
isUserMessage = activeKeyPair.pubKey.toHex() == groupEvent.pubKey
```

`groupEvent.pubKey` is the kind:445's signer, and that is a fresh random ephemeral
key on every send — `NostrSignerInternal(KeyPair())` in
`encryptAndSendMarmotInnerEvent` — so it could never equal the active key. It was
false for every Marmot message in every room, which meant every message a member
sent themselves rendered as somebody else's: wrong side of the transcript, wrong
colour, and no delivery status, which is drawn only for our own lines. All fourteen
now read `senderIdentity`. For the four results that carry no leaf index — the
commit and proposal statuses — it is null and yields false, exactly as before.

## The message's identity is the rumor id

The queued `MarmotInnerEvent` for a direct message **is the rumor** — kind:14,
plaintext, p-tagged — so its id is a hash of exactly the fields the recipient will
have after unwrapping.

This matters in two places. It keeps the outbound link intact, which is the
difference between a message being sent and silently not being sent (see
[Two bugs this uncovered](#two-bugs-this-uncovered)). And it gives the recipient a
stable id for dedupe across redelivery and re-sync.

It does **not** give sender and recipient a shared identity for the same message the
way an identity-keyed wrap would, because the sender never re-derives the rumor from
an echo they cannot open. Two devices belonging to the sender do not converge; they
simply do not both have the message.

The wire event — the wrap — has its own id, so a direct message leaves two rows on a
recipient's device: one for the artifact that travelled and one for the message it
carried.

## Timestamps are not fuzzed

NIP-59 randomises the wrap's and the seal's `created_at` by up to two days to
frustrate correlation at a relay, and both `GiftWrapEvent.create` and
`SealedRumorEvent.create` default to `TimeUtils.randomWithTwoDays()`.

The wrap never reaches a relay, and the kind:445 around it already carries the true
time. Fuzzing would do nothing but scatter the *"sent a private message"* line up to
two days out of position in every other member's transcript. `MarmotDirectMessage`
passes the real message time to all three layers — a default to override, not one to
accept, and the easiest thing here to get wrong by omission. There is a test for it.

## Three decisions

| decision | taken | what it costs |
|---|---|---|
| Is the recipient visible to the group? | yes — the `p` tag stays on the wrap | The group learns who messages whom, and how often. Omitting the tag is genuinely cheap here: the recipient decrypts against the wrap's own throwaway pubkey, so they need no tag to find their own mail, and one failed NIP-44 decrypt per member per message is the whole cost. What it takes is the named bystander line — *"sent a private message"* with no recipient — and any ordering by conversation. Worth revisiting; not free, but close. |
| Is the wrap signed? | yes — by the throwaway key, per NIP-59 | Nothing, which is the point. The signer is meaningless and discarded, so the signature attributes nothing, and keeping it means the payload is a real NIP-59 gift wrap that `GiftWrapEvent.create` builds and `unwrapOrNull` opens with no special cases. |
| What kind is the rumor? | `ChatMessageEvent.KIND` (14) | Group messages here are kind:9 (`ChatEvent`) — `sendChatMessage` maps 14 → 9 on the way in for an ordinary message and leaves a direct message at 14. It is a NIP-17 chat message that happens to travel inside a group, and the kind is what tells the two apart on the way back in. Costs one more arm in the inbound switch. |

## The path a message takes

**Out.** `sendChatMessage` resolves the recipient against the room's participants —
refusing a non-member, whose wrap nobody could open, and refusing yourself, whose
wrap could never be read back — and writes two rows: the queued `MarmotInnerEvent`
(the rumor) and the `ChatMessage` holding the plaintext. The notary picks the queued
row up, `MarmotOutboundDao` calls `MarmotDirectMessage.wrap` instead of assembling a
bare rumor, and everything downstream is unchanged. Once sent, the plaintext is
scrubbed from the queued row: it is already on the `ChatMessage`, and a second copy
would be cleartext left in a table that otherwise holds nothing but wire events.

**In.** `MarmotInboundManager` decrypts and resolves the sender's identity, and
`MarmotDirectMessage.classify` decides what the wrap is to this device — ours,
readable, unreadable, or rejected. `ChatMessage.directMessage` turns that into rows.
The split is so the decision can be tested: only the filing needs a database, and
Room-backed code cannot be unit-tested here. The recipient opens the wrap,
validates the seal against `senderIdentity`, stores the rumor as its own
`MarmotInnerEvent` keyed on the rumor's id, and gets the words. A bystander cannot
open it and gets a line with no content. The sender gets nothing, because their
local row is the only copy.

A failed validation drops the message and logs; it does not throw. The caller is
inside `storeNostrEvent`'s transaction, and a forged direct message should cost its
own line, not the whole event.

**On screen.** Tapping somebody else's message in a group offers *Reply privately*,
which arms the composer; sending clears it. An armed composer shows a chip naming
the recipient, a placeholder that says who it is going to, and a tinted field, and
the chip's close button is the one tap back to the room. Three signals rather than
one because the failure this guards against — sending to the room what was meant for
one person, or the reverse — cannot be taken back once it is on the wire.

## Two bugs this uncovered

### The wrap's id is not the rumor's

`encryptAndSendMarmotInnerEvent` found the chat message to link and broadcast with
`getChatMessagesByMarmotInnerEventId(innerEvent.id)` — the *recomputed* id. For a
direct message that is the wrap's id, not the queued rumor's, so the lookup returned
null, the `ChatMessage` was never linked, **and no `BroadcastNostrEventRequest` was
ever inserted**. The message would have been encrypted, persisted, and never sent,
with no error anywhere.

It now keys on `marmotInnerEvent.id` — what `sendChatMessage` wrote into
`ChatMessage.marmotInnerEventId`, and identical to `innerEvent.id` for every
non-direct message, so nothing else moved.

### `isUserMessage` was always false for Marmot messages

Covered under [Attribution comes from MLS](#attribution-comes-from-mls-not-from-the-payload).
It stopped being merely cosmetic here: with nothing in the payload naming the
sender, `senderIdentity` is the only source of attribution there is.

## Where it lives

| file | what |
|---|---|
| `nostr/MarmotDirectMessage.kt` | wrap / open, the pure seam both directions call |
| `commonTest/.../MarmotDirectMessageTest.kt` | the envelope: ten cases, against real secp256k1 and real NIP-44 |
| `commonTest/.../MarmotDirectMessageDeliveryTest.kt` | what a device does with an arriving wrap, including every forgery it must refuse |
| `commonTest/.../MarmotMip03CarveOutTest.kt` | the kind:1059 exemption and its edges |
| `managers/MarmotInboundManager.kt` | the kind:1059 carve-out; requires the sender identity |
| `database/model/ChatMessage.kt` | `TYPE_DIRECT_MESSAGE`; the kind:1059 arm and its three outcomes |
| `database/dao/NostrDao.kt` | resolves `senderIdentity` and passes it in |
| `database/repository/DatabaseChatRepository.kt` | queues the rumor; refuses to seal an MLS room's payload, except a Welcome |
| `database/dao/MarmotOutboundDao.kt` | wraps on the way out; scrubs the plaintext |
| `database/model/MarmotInnerEvent.kt` | `directMessageRecipientPublicKey`, the outbound signal |
| `ui/view/model/ChatMessageListViewModel.kt` | armed state; the two renderings |
| `ui/composable/ChatRoomMessagingScreen.kt` | the recipient chip |

Untouched, deliberately: `NostrNip17Dao`, `GiftWrapMessage`, `GiftWrapSeal`,
`GiftWrapPayload`, and every broadcast path.

## What this does not do

Each of these will be reported as a bug at some point. They are not.

**The group learns that a direct message happened, and to whom.** Only the contents
are private, and the sender is authenticated by MLS whatever the wrap says. That is
the design, and the UI should say so in words somewhere a user meets before their
first private message — it does not yet.

**The sender cannot read their own messages anywhere they were not typed.** The
throwaway key is gone. A reinstall or a second device recovers the recipient's half
of a conversation and none of its own.

**Other Marmot clients drop these messages.** Until the carve-out is upstream, a
group needs every member on a client that carries it, and an unpatched member fails
silently.

**The inner layer is not forward secret.** The kind:445 envelope inherits MLS epoch
forward secrecy; the NIP-44 layers inside do not. A recipient's identity key that
leaks opens every direct message they still hold, including ones sent years earlier.
This is strictly weaker than the group messages sitting beside them in the same
room.

**A removed member keeps what they already have.** Removal advances the epoch; it
does not reach back into their device.

**Disappearing messages apply at the envelope only.** The group's
`disappearingMessageSecs` puts a NIP-40 expiration on the kind:445, which relays
honour. Local rows are unaffected, exactly as for group messages today.

**One recipient per message.** Several would mean several wraps, and so several
bystander lines for one message. Worth doing; worth designing first.

**No reactions, receipts, replies or attachments.** A reply needs an `e` tag inside
the rumor and is a small addition later. A reaction is a design question rather than
a coding one, because the reaction itself would be visible to the group.

**No member picker.** The only way to start a private message is to reply to one the
member already sent, so you cannot open a conversation with somebody who has not
spoken.

**Three things have no automated test**, all of them for want of infrastructure
rather than by choice. The recipient validation in `sendChatMessage` (refusing a
non-member, refusing yourself) and the outbound id lookup both need a database; the
invariant the lookup depends on — that the queued row's id is the rumor's — is
tested in its place. The two transcript renderings need Compose UI testing, which
this project has no dependency on. Anything touching a real `MlsGroup` is likewise
untested: the carve-out is tested through `mip03Rejection`, not through a group.
