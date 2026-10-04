package dev.ellipog.armature.api.teams;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two shared numbers, asserted where the command's message and the manager's refusal both read
 * them.
 *
 * <p>There is not much to a name rule, which is exactly why it is worth a test: it is read by a
 * command building a Brigadier argument, a manager writing NBT, and a header drawing text, and the
 * failure when the three disagree is a name that stores but cannot be typed, or displays but cannot
 * be saved. The boundaries below are the ones a caller can get wrong by off-by-one.
 */
class TeamLimitsTest {

    @Test
    @DisplayName("a name is valid when it is non-blank, short enough, and free of control characters")
    void validNames() {
        assertTrue(TeamLimits.isValidName("the crew"));
        assertTrue(TeamLimits.isValidName("  padded  "),
                "surrounding whitespace is trimmed before the length is judged, so it is not a way to "
                        + "sneak past the limit");
        assertTrue(TeamLimits.isValidName("a".repeat(TeamLimits.MAX_NAME_LENGTH)),
                "exactly the limit is inside it");
    }

    @Test
    @DisplayName("blank, over-long and control-carrying names are refused")
    void invalidNames() {
        assertFalse(TeamLimits.isValidName(null));
        assertFalse(TeamLimits.isValidName(""));
        assertFalse(TeamLimits.isValidName("   "),
                "a blank name displays as the owner's name, which reads as a bug rather than as input");
        assertFalse(TeamLimits.isValidName("a".repeat(TeamLimits.MAX_NAME_LENGTH + 1)));
        assertFalse(TeamLimits.isValidName("two\nlines"),
                "a control character would travel into a command argument, a payload line and a "
                        + "header at once -- the payload's line format is the one that cannot survive it");
    }

    @Test
    @DisplayName("fullness is about the limit, and an unknown limit never refuses")
    void fullness() {
        assertFalse(TeamLimits.isFull(TeamLimits.MAX_MEMBERS - 1, TeamLimits.MAX_MEMBERS));
        assertTrue(TeamLimits.isFull(TeamLimits.MAX_MEMBERS, TeamLimits.MAX_MEMBERS));
        assertTrue(TeamLimits.isFull(TeamLimits.MAX_MEMBERS + 1, TeamLimits.MAX_MEMBERS),
                "a loaded party over the limit is still full -- the check runs on joins, not on load");

        // Zero is the manager's answer for "this source cannot say", and a source that cannot say must
        // not be the reason a join is refused: the source underneath refuses it itself if it wants to.
        assertFalse(TeamLimits.isFull(99, 0));
        assertFalse(TeamLimits.isFull(0, 0));
    }
}
