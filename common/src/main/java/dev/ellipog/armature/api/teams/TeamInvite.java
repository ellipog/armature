package dev.ellipog.armature.api.teams;

import java.util.UUID;

/**
 * An invitation, and where it came from.
 *
 * <h2>Why this is not just the invited player's id</h2>
 *
 * <p>Two facts a row in a party panel needs and a bare set cannot carry: <b>who sent it</b>, so the
 * invitee knows whose party this is and who to ask about it, and <b>when</b>, so an invitation that
 * arrived a minute ago can be told from one that has been sitting there since last week. A set of
 * ids can say neither, which is what it was.
 *
 * <h2>Zero means unknown, and that is the honest value</h2>
 *
 * <p>{@code at} is game time in ticks, matching {@link Team#createdAt}, and a source that cannot say
 * passes <b>zero</b>. A foreign parties mod keeps its own invitation list and none of them publish a
 * timestamp; the alternative to zero is a made-up time that sorts and reads as authoritative and
 * that nothing can check. {@link #hasTime()} is the question a display asks before formatting one.
 *
 * @param inviter who sent the invitation. For a source that cannot distinguish, the party's owner —
 *                never null, because an invitation from nobody is worse than an approximate one
 * @param at      game time in ticks the invitation was sent, or zero when the source cannot say
 */
public record TeamInvite(UUID inviter, long at) {

    /** Whether the source could say when this was sent. See the class note: zero means unknown. */
    public boolean hasTime() {
        return at > 0L;
    }
}
