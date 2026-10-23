package com.machankura.bennu.compose.nostr.lightning

import com.vitorpamplona.quartz.nip01Core.core.Kind

/**
 * The nostr kinds a group Lightning wallet is carried on: its Iceberg key
 * ceremony, its two group-signed statements, and the traffic between the
 * operator's node and the members' co-signers. See
 * `docs/group-lightning-wallet.md`.
 *
 * These are **rumor** kinds, like every other family here: each one only ever
 * exists as a Marmot inner event, MLS-encrypted to the room, and none is ever
 * broadcast to a relay. The replaceable semantics the 3xxxx range implies never
 * apply.
 *
 * ```
 *  proposer    --[ 30333 ceremony proposal ]-> everyone   "let's make a t-of-n Iceberg key"
 *  participant --[ 30334 DKG round 1       ]-> everyone   commitments to seed contributions
 *  participant --[ 30335 DKG round 2       ]-> everyone   contributions, each encrypted to its holder
 *  participant --[ 30336 DKG round 3       ]-> everyone   public share + transcript signature
 *  anyone      --[ 30337 ceremony failure  ]-> everyone   abort + blame
 *  group       --[ 30338 wallet statement  ]   FROST-signed, never broadcast
 *  group       --[ 30339 operator statement]   FROST-signed, never broadcast
 *  opener      --[ 30340 node seed         ]-> each participant, encrypted to them
 *  operator    --[ 30341 signing request   ]-> everyone
 *  participant --[ 30342 echo              ]-> everyone   (label, message hash)
 *  participant --[ 30343 nonce batch       ]-> everyone   round-one contributions
 *  participant --[ 30344 signature share   ]-> everyone   round two
 *  operator    --[ 30345 snapshot          ]-> everyone   balance, channels, payments
 *  group       --[ 30346 authorisation     ]   FROST-signed: pay, close, force-close, splice-out
 *  member      --[ 30347 invoice request   ]-> everyone
 *  operator    --[ 30348 invoice           ]-> everyone
 *  old operator--[ 30349 stopped           ]-> everyone   its last commitment numbers
 * ```
 *
 * ### Why 30333
 *
 * 30330-30332 were the highest kinds in use when this family was reserved, and
 * the note on `FrostSigningEvents` asks the next family not to rely on routing
 * to keep it apart from the others. So this one is a fresh run with nothing
 * else in it, and `GroupLightningEventsTest` asserts that none of it overlaps any
 * kind the app already dispatches on.
 */
object GroupLightningEvents {
    val CEREMONY_PROPOSAL: Kind = 30333
    val DKG_ROUND_1: Kind = 30334
    val DKG_ROUND_2: Kind = 30335
    val DKG_ROUND_3: Kind = 30336
    val CEREMONY_FAILURE: Kind = 30337

    val WALLET_STATEMENT: Kind = 30338
    val OPERATOR_STATEMENT: Kind = 30339
    val NODE_SEED: Kind = 30340

    val SIGNING_REQUEST: Kind = 30341
    val ECHO: Kind = 30342
    val NONCE_BATCH: Kind = 30343
    val SIGNATURE_SHARE: Kind = 30344

    val SNAPSHOT: Kind = 30345
    val AUTHORISATION: Kind = 30346
    val INVOICE_REQUEST: Kind = 30347
    val INVOICE: Kind = 30348
    val STOPPED: Kind = 30349

    /** The Iceberg ceremony's kinds, for routing to its ritual manager. */
    val CEREMONY: Set<Kind> = setOf(CEREMONY_PROPOSAL, DKG_ROUND_1, DKG_ROUND_2, DKG_ROUND_3, CEREMONY_FAILURE)

    /** The co-signing protocol's kinds, for routing to the co-signer. */
    val CO_SIGNING: Set<Kind> = setOf(SIGNING_REQUEST, ECHO, NONCE_BATCH, SIGNATURE_SHARE)

    /** Everything the operator and the members tell each other outside the two protocols. */
    val WALLET_TRAFFIC: Set<Kind> = setOf(NODE_SEED, SNAPSHOT, INVOICE_REQUEST, INVOICE, STOPPED)

    /** The three kinds the group signs with its FROST key, filed as `GroupSignedEvent`s. */
    val GROUP_SIGNED: Set<Kind> = setOf(WALLET_STATEMENT, OPERATOR_STATEMENT, AUTHORISATION)

    val ALL: Set<Kind> = CEREMONY + CO_SIGNING + WALLET_TRAFFIC + GROUP_SIGNED
}
