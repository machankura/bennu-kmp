package to.curare.compose.nostr.subgroup

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.tags.dTag.DTag
import com.vitorpamplona.quartz.nip01Core.tags.people.PTag
import to.curare.compose.managers.SharedKeyDerivation
import to.curare.compose.nostr.frost.GroupKeyStateEvent
import to.curare.compose.nostr.frost.tags.FrostDerivationPathTag
import to.curare.compose.nostr.subgroup.tags.SubgroupKeyTag
import to.curare.compose.nostr.subgroup.tags.SubgroupParentTag

/**
 * A group saying, with a quorum, that another group is its child.
 *
 * The whole of what a subgroup relationship is. A child has its own ChillDKG
 * key, its own room, its own quorum and its own admins -- nothing is inherited
 * and nothing is delegated -- and this is the one thing that ties it to a
 * parent:
 *
 * > The group holding key `P` said, with a quorum, that the room `C` is its
 * > child.
 *
 * Produced by a `FrostSigningEvents.PROPOSAL` in the **parent's** room, so the
 * author is the parent room's id and no single member can make one. It then
 * travels, whole, on the child's `GroupKeyStateEvent` -- see
 * `GroupKeyStateManager.stateFrom`, which is where a state carrying one earns
 * its parentage or is dropped.
 *
 * ```
 *  ceremony among the child's admins  -> threshold key K, and so C = marmotGroupId(K)
 *  coordinator --[ 30320 over a 30329 ]-> the parent's room
 *  ...the parent's quorum signs...
 *  every parent device holds the certificate     "C is ours"
 *  coordinator --[ 30320 over a 30326 ]-> the child's ceremony room, carrying it
 * ```
 *
 * ### Content is the id; the tags are what make it checkable
 *
 * Taken literally a birth certificate is 32 opaque bytes, and a parent admin
 * would be asked to sign a number they cannot check, produced by a ceremony most
 * of them were not in, on behalf of people they have only the coordinator's word
 * about. So [assembleTags] carries the child's threshold key, the derivation
 * path, its founding admins and its name. The signature covers all of it -- an
 * event id hashes over its tags -- so nothing is added to the claim by putting
 * it there, only to what a signer can see before agreeing.
 *
 * The one that earns its place is [SubgroupKeyTag]: with it a signer's device
 * can check `marmotGroupId(key, path) == content` for itself, which is the
 * difference between approving a hash and approving a group.
 *
 * ### What [certifies] deliberately does not check
 *
 * The name and the `p` tags are the **founding** roster. A certificate is signed
 * once; members join and leave and rooms get renamed afterwards, and none of
 * that reaches a signature already made. Comparing either against a room's
 * current state would start rejecting a valid certificate the first time
 * somebody joined the child, and the rejection would look exactly like a
 * forgery. They are shown as history and are never a live roster.
 *
 * ### Replaceable, by this app rather than by a relay
 *
 * Like every kind in this family it only ever exists inside an encryption a
 * relay cannot open, so the addressable semantics of the 3xxxx range never fire.
 * [DTag] is the child's room id and the newest certificate per child wins, which
 * the local store settles on its own. That matters in practice: two parent
 * admins can propose a certificate for the same child at once, and two true
 * statements about one child should fold together rather than accumulate.
 */
object SubgroupBirthCertificateEvent {
    /**
     * Sits past `GroupKeyStateEvent` (30326) and the chronicle pair
     * (30327-30328), in the same private inner-event space. Like them it says
     * something about a room rather than carrying the room's work.
     */
    val KIND: Kind = 30329

    fun isSubgroupBirthCertificateKind(kind: Kind): Boolean = kind == KIND

    /**
     * The tags for a certificate of [subgroupChatRoomId] by [parentChatRoomId].
     *
     * The child's room id goes on as the `d` tag as well as in the content, so
     * the event is self-addressing and a second certificate for the same child
     * replaces the first rather than standing beside it.
     *
     * [adminPublicKeys] are the child's founding admins, and [name] is what the
     * coordinator called it. Both are for the signer to read; see the class
     * comment for why neither is checked afterwards.
     */
    fun assembleTags(
        subgroupChatRoomId: String,
        parentChatRoomId: String,
        thresholdPublicKey: HexKey,
        adminPublicKeys: List<HexKey>,
        name: String,
        path: List<Long> = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    ): Array<Array<String>> = arrayOf(
        DTag.assemble(subgroupChatRoomId),
        SubgroupParentTag.assemble(parentChatRoomId),
        SubgroupKeyTag.assemble(thresholdPublicKey),
        FrostDerivationPathTag.assemble(path),
        arrayOf(NAME_TAG_NAME, name)
    ) + adminPublicKeys.distinct().map { PTag.assemble(it, null) }

    /** The child this certificate is about, or null if it names none. */
    fun parseSubgroupChatRoomId(tags: Array<Array<String>>): String? =
        tags.firstOrNull { it.size > 1 && it[0] == DTag.TAG_NAME }?.get(1)?.ifBlank { null }

    /** The parent doing the certifying, or null if it names none. */
    fun parseParentChatRoomId(tags: Array<Array<String>>): String? =
        tags.firstNotNullOfOrNull(SubgroupParentTag::parse)?.parentChatRoomId

    /** The child's threshold key, or null if it carries none of the right shape. */
    fun parseThresholdPublicKey(tags: Array<Array<String>>): HexKey? =
        tags.firstNotNullOfOrNull(SubgroupKeyTag::parse)?.thresholdPublicKey

    /** The derivation path, or null if it carries none or an unwalkable one. */
    fun parsePath(tags: Array<Array<String>>): List<Long>? =
        tags.firstNotNullOfOrNull(FrostDerivationPathTag::parse)?.path

    /**
     * The child's founding admins, in the order they were written.
     *
     * `parseKey` rather than `parse`, because the relay hint a full `PTag` would
     * carry is not part of what the parent agreed to -- and a hint that failed to
     * normalise would drop an admin from the list rather than the hint from the
     * admin.
     */
    fun parseAdminPublicKeys(tags: Array<Array<String>>): List<HexKey> =
        tags.mapNotNull(PTag::parseKey)

    /** What the child was called at founding, or null if it was not named. */
    fun parseName(tags: Array<Array<String>>): String? =
        tags.firstOrNull { it.size > 1 && it[0] == NAME_TAG_NAME }?.get(1)?.ifBlank { null }

    /**
     * Whether [event] is a certificate of [subgroupChatRoomId] that
     * [parentChatRoomId] actually signed.
     *
     * The whole of the trust in a parent link, and six questions:
     *
     *  1. it is a certificate at all;
     *  2. it is about this child, in the content and in the `d` tag, which have
     *     to agree -- the content is the claim and the `d` tag is the address,
     *     and one pointing somewhere the other does not is not a thing to guess
     *     the meaning of;
     *  3. it names this parent;
     *  4. the child's id rederives from the key and path the certificate carries,
     *     so a certificate cannot be pointed at a room the key it names did not
     *     make;
     *  5. the parent room signed it -- author, id and signature, through the
     *     check `GroupKeyStateEvent` already makes of a room's own signature;
     *  6. all of it inside a `runCatching`, because every input is off the wire:
     *     a key that is not a point, a signature that is not 64 bytes, hex that
     *     is not hex. All of them mean the same thing here, which is no.
     *
     * Point 5 is not reimplemented. `isSignedByRoom` already asks "did *this
     * room* sign this", and a room id is a public key -- that is the economy
     * `docs/member-chronicle.md` is built on and it applies unchanged.
     *
     * Hex is compared case-insensitively, as the author check is. A certificate
     * that differed in case from what it was signed over would fail point 5
     * anyway, so this only decides whether a caller holding the same id in
     * another case gets a silent drop.
     */
    fun certifies(
        event: Event,
        subgroupChatRoomId: String,
        parentChatRoomId: String
    ): Boolean = runCatching {
        if (event.kind != KIND) return false

        if (!event.content.trim().equals(subgroupChatRoomId, ignoreCase = true)) return false

        val addressed = parseSubgroupChatRoomId(event.tags) ?: return false
        if (!addressed.equals(subgroupChatRoomId, ignoreCase = true)) return false

        val named = parseParentChatRoomId(event.tags) ?: return false
        if (!named.equals(parentChatRoomId, ignoreCase = true)) return false

        val thresholdPublicKey = parseThresholdPublicKey(event.tags) ?: return false
        val path = parsePath(event.tags) ?: return false

        val derived = SharedKeyDerivation.marmotGroupId(thresholdPublicKey, path)
        if (!derived.equals(subgroupChatRoomId, ignoreCase = true)) return false

        GroupKeyStateEvent.isSignedByRoom(event, parentChatRoomId)
    }.getOrDefault(false)

    /**
     * The name tag. A bare two-element tag with no class of its own, because
     * nothing but the proposal screen and the subgroup list ever reads it and
     * neither decides anything on it.
     */
    const val NAME_TAG_NAME = "name"
}
