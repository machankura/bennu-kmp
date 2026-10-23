# curare docs

Notes on the parts of this app whose behaviour is not recoverable by reading the
code alone — where the reasoning lives in a protocol, a failure mode that is
silent, or a decision that looked arbitrary and was not.

| document | covers |
|---|---|
| [shared-key-ceremony.md](./shared-key-ceremony.md) | ChillDKG over NIP-17: the rounds, the approval gates, the chat transcript, participant ordering |
| [shared-key-derivation.md](./shared-key-derivation.md) | deriving further keys from the group's threshold key with FROST tweaks — why not BIP32, why no chain code, and the one rule that must not be broken |
| [subgroups.md](./subgroups.md) | a group making another group — the four ceremonies, what the parent's signature actually covers, and why the child's key is fresh rather than derived |
| [frost-batch-signing.md](./frost-batch-signing.md) | signing several events in one ceremony — why one nonce can never cover two messages, and the phased schema, wire and UI work that follows from it |
| [group-chain-wallet.md](./group-chain-wallet.md) | an on-chain wallet the group's threshold key holds, modelled on Phoenix's final wallet — why the path is BIP86's unhardened tail under a public account step, why each input is a batch item with its own cache, and the seven checks a member makes before signing a spend |
| [frost-session-resend.md](./frost-session-resend.md) | asking the members who never got a signing proposal to look again — why a proposer can repeat only what it wrote, why a member's availability signal needs no repeating, and the one half that has to come from the silent member's own device |
| [marmot-membership.md](./marmot-membership.md) | how members join an MLS group, and the epoch race that makes a missing member look like a successful invite |
| [member-chronicle.md](./member-chronicle.md) | handing a member added after the work was done the group's signed record — why the events are not on the wire at all, and why the room's id is enough to verify them |
| [marmot-direct-messages.md](./marmot-direct-messages.md) | a one-to-one message inside a group as a stock NIP-59 gift wrap — what its MIP-03 carve-out costs, why the sender cannot read their own, and the one query that would broadcast it |
| [mls-skipped-keys.md](./mls-skipped-keys.md) | why a group event that arrives a moment late is dropped for good, which flows trigger it, the quartz fix, and the partial mitigation in this app |
| [long-running-sync.md](./long-running-sync.md) | the chat subscriptions that stay open instead of pulling once per screen — why the request queue could not simply hold one, and how the group filter follows the room list |
| [dead-code.md](./dead-code.md) | code in the sync and relay stack that nothing calls, why each piece is still there, and which of it is a bug rather than a leftover |
| [nsec-sign-in.md](./nsec-sign-in.md) | signing in with an existing nostr key — why an nsec can never have a wallet behind it, the ten call sites that make it small, and the sign-in machine that was already built and unreachable |
| [npub-sign-in.md](./npub-sign-in.md) | signing in with only a public key — what a key that cannot sign can still see here, why that is a preview rather than a browser, and the four decisions the word settles |
| [multiple-profiles.md](./multiple-profiles.md) | several profiles on one device — why a profile is a credential and a seed is a wallet attached to one, why a switch is a restart rather than a swap, the two entrances the app lacks, and the inbox a switch would silently lose |
| [jvm-target.md](./jvm-target.md) | what desktop support cost, phased — why the native chain was already done, why an empty source set in our phoenix fork was the real blocker, and why DAO tests need none of it |
| [npub-profile-preview.md](./npub-profile-preview.md) | showing the person before a direct message is started from a pasted npub — why the preview is a screen keyed by public key, what "found" means when a row can be a placeholder, and the phase that makes the button honest about a key package that never comes |
| [material-design-conformance.md](./material-design-conformance.md) | what the M3 foundations actually require, measured against all 43 screens — the colour pairing that renders the app's own proposals invisible, and eight phases that put the decisions back in the theme |
| [marmot-v2.md](./marmot-v2.md) | bringing the group layer onto the Marmot current profile after the quartz 1.15.2 bump — the nine rules that reach this app, why the room's id and the routing id have to become two values, nine phases from reading both profiles to publishing before applying, and the one fork the library cannot yet settle |

Start with the ceremony if you are new to this area; the Marmot notes all assume it.
Read the skipped-keys note before debugging any "the other device never got it"
report — it is silent, and it looks like every other kind of delivery failure. The
resend note is the recovery for it on a signing session, and says which delivery
failures a resend repairs and which heal on their own. The
sync note stands alone, and the dead-code inventory reads as a follow-up to it. The
batch-signing note is a phased plan that has been built: read it after the
derivation note, whose one rule is the same one it is built around. The member
chronicle note is a phased plan that has not been built, and reads as the
membership note's unanswered half: what a member who joins late can be given,
and the one thing they cannot. The
jvm-target note is unrelated to all of them: it is a build and packaging story.
The subgroups note is a phased plan that has been built; it assumes both
shared-key notes and reads as the ceremony's second half — what a group does
once it has a key, and what it can say about a group that does not yet.
The Material Design note is a phased plan that has not been built, and is the
only one about what the app looks like rather than what it does; read the
jvm-target note first if you want to know why its adaptive-layout phase exists.
The nsec sign-in note is a phased plan that has been built; it inherits the
key-storage decision from the jvm-target note and drives the navigation state
machine `NavigationViewModel.processLocalAccount` implements, so read it with the
code open, and read its table of where the build chose differently first.
The npub sign-in note is a phased plan that has been built, and reads as that
plan's out-of-scope note answered: it takes the `Identity` type and the sign-in
machine as given and asks what an identity with no secret is for, before it asks
how to build one; read its table of where the build chose differently first.
The multiple-profiles note is a phased plan that has been built, and reads as the
third of the sign-in notes: it asks what happens when the device holds two
identities, and its first phase changes one rule the other two share — a seed's
key becomes a credential like any other — so read it with `StoredIdentity.merge`,
`SovereignWalletViewModel.switchToIdentity` and the startup screen open, and read
its table of where the build chose differently first.
The npub profile preview note is a phased plan that has been built, and is the
smallest of the plans: it changes the entrance to the direct-message flow and
nothing past it, so read it with `StartDirectMessageToNpubOrNip05Dialog` and
`ChatRoomMessagingViewModel.initiateNewChat` open, its four decisions before its
four phases, and its table of where the build chose differently first.
The group chain wallet note is a phased plan that has been built. It assumes
both shared-key notes and the batch-signing note, and reads as the derivation
note's one rule with money behind it; read its derivation section before any
phase, since every phase after the first is built on that path, and its table of
where the build chose differently before any of the code.
The Marmot v2 note is a phased plan that has been built, and is the largest of
them: it assumes the membership and derivation notes, takes the quartz 1.15.2 bump
as its Phase 0, and is ordered so that the phases that reach other clients come
before the ones that make the app's forks settle the way theirs do; read its table
of nine rules and its two constraints before any phase, and its table of where the
build chose differently before any of the code -- its first row, that a room's
profile picks its pipeline, is the shape of everything that follows, and its last
is the one fork the library cannot yet settle.
