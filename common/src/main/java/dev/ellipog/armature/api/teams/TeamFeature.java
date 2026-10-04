package dev.ellipog.armature.api.teams;

/**
 * An optional thing a team source may be able to do.
 *
 * <h2>Why a capability instead of another abstract method</h2>
 *
 * <p>{@link MutableTeamManager} makes "this source really can write" a compile-time fact, and that
 * works because every source either writes all six structural operations or none. The operations
 * this enum names are different: they are the ones a foreign parties mod may implement some of.
 * Open Parties and Claims can invite and kick but <b>cannot rename</b> a party — its public API has
 * no setter — and FTB Teams is read-only here by design. Forcing every adapter to declare
 * {@code rename} and throw would turn "cannot" into a runtime surprise, which is the shape the
 * existing interfaces exist to avoid.
 *
 * <p>So a caller asks {@link TeamManager#supports} before offering the control. A panel that draws
 * a rename pencil on a source that cannot rename is drawing a button whose only possible outcome is
 * the refusal message — worse than a missing control, because the player concludes the mod is
 * broken rather than that the source mod owns this.
 *
 * <h2>What each one gates</h2>
 *
 * <ul>
 *   <li>{@link #RENAME} — {@link TeamManager#rename}</li>
 *   <li>{@link #TRANSFER} — {@link TeamManager#transferOwnership}</li>
 *   <li>{@link #POLICY} — {@link TeamManager#setPolicy}, and the two settings toggles in a panel</li>
 *   <li>{@link #OPEN_JOIN} — {@link TeamManager#joinPublic}: whether a party can be joined without
 *       an invitation at all. Separate from {@code POLICY} because a source could allow joining a
 *       public party while still having no API to change the flag</li>
 *   <li>{@link #INVITE_DECLINE} — {@link TeamManager#declineInvite}</li>
 *   <li>{@link #INVITE_CANCEL} — {@link TeamManager#cancelInvite}</li>
 * </ul>
 *
 * <p>The structural six are <b>not</b> in here: they are governed by
 * {@link TeamManager#managesMembership()} and {@link MutableTeamManager}, and answering two
 * questions about the same operation is two answers that can disagree.
 */
public enum TeamFeature {

    /** The party's name can be changed through this source. */
    RENAME,

    /** Ownership can be handed to another member through this source. */
    TRANSFER,

    /** The party's {@link TeamPolicy} can be changed through this source. */
    POLICY,

    /** A public party can be joined without an invitation through this source. */
    OPEN_JOIN,

    /** An invitee can decline an invitation through this source. */
    INVITE_DECLINE,

    /** An invitation can be withdrawn before it is answered through this source. */
    INVITE_CANCEL
}
