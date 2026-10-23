package com.machankura.bennu.compose.database.model.types

/**
 * The three things an Iceberg ceremony asks a member before their device publishes anything of
 * theirs, in order. The same three gates the ChillDKG ceremony has, for the same reasons.
 */
enum class IcebergApprovalStep {
    /**
     * Publish this device's commitments, which puts its owner in the ceremony. Joining then going
     * quiet holds the ceremony open for everybody, since every seed needs all of its holders.
     */
    JOIN,

    /**
     * Send this device's contributions to the seeds it holds, each encrypted to the other holders.
     * From here the member's own randomness is shaping a key they will help sign with.
     */
    REVEAL,

    /**
     * Publish this device's public share and the hash of the transcript it saw. A real check: two
     * members shown different commitments sign different transcripts, and the ceremony refuses to
     * finish on transcripts that disagree.
     */
    CONFIRM,
}
