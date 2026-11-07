package app.morphe.extension.reddit.patches.archive;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProfileOwnershipTest {
    @Test public void ownPostsAndCommentsIgnoreCaseAndUsernamePrefixes() {
        for (boolean comments : new boolean[]{false, true}) {
            for (String name : new String[]{"Example_User", "u/Example_User", "/u/Example_User", "/U/Example_User"}) {
                ArchiveProfileContext profile = new ArchiveProfileContext(new Object(), name, comments, "u/EXAMPLE_USER");
                assertTrue(profile.ownProfile);
                assertFalse(profile.archiveAllowed());
            }
        }
    }

    @Test public void onlyTheActiveAccountIsExcluded() {
        assertTrue(new ArchiveProfileContext(new Object(), "other_user", false, "active_user").archiveAllowed());
        assertTrue(new ArchiveProfileContext(new Object(), "active_user", true, "other_user").archiveAllowed());
        assertFalse(new ArchiveProfileContext(new Object(), "active_user", true, "active_user").archiveAllowed());
    }

    @Test public void unknownAndAnonymousOwnershipKeepVisitorBehavior() {
        for (String owner : new String[]{null, "", "[deleted]", "not a username", "u/", "a"}) {
            assertFalse(ArchiveEligibility.ownProfile("example_user", owner));
            assertTrue(new ArchiveProfileContext(new Object(), "example_user", false, owner).archiveAllowed());
        }
        assertFalse(ArchiveEligibility.ownProfile(null, null));
    }

}
