package com.machankura.bennu.compose.text

import fr.acinq.bitcoin.Satoshi

/**
 * An amount in sats, its thousands grouped: `120,000`.
 *
 * Sats rather than bitcoin, everywhere the group's wallet shows an amount: a
 * signer comparing a fee to an output is comparing two integers, and a decimal
 * point eight places in is the easiest digit on the screen to misread.
 */
fun Satoshi.grouped(): String {
    val digits = sat.toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (sat < 0) "-$grouped" else grouped
}
