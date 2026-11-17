package app.morphe.extension.reddit.patches;

import app.morphe.extension.shared.ResourceUtils;

public final class NsfwNavigationPatch {
    private NsfwNavigationPatch() {}

    public static int goBackLabelResource() {
        return ResourceUtils.getStringIdentifier("action_go_back");
    }

}
