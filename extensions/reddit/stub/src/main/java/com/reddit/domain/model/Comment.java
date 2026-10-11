package com.reddit.domain.model;

public final class Comment extends IComment {
    public String getKindWithId() {
        throw new UnsupportedOperationException("Stub");
    }

    public boolean getCollapsed() {
        throw new UnsupportedOperationException("Stub");
    }

    /**
     * Added during patching by the Remember collapsed comments patch.
     */
    public void patch_setCollapsed(boolean collapsed) {
        throw new UnsupportedOperationException("Stub");
    }

    /**
     * Copy of the original getCollapsed(), added during patching by the Remember collapsed comments patch.
     */
    public boolean patch_getRawCollapsed() {
        throw new UnsupportedOperationException("Stub");
    }
}
