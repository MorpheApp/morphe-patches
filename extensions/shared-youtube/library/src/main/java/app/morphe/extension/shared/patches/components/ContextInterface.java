/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1919
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches.components;

/**
 * Interface to use obfuscated methods.
 */
public interface ContextInterface {
    // Method is added during patching.
    StringBuilder patch_getPathBuilder();
    String patch_getIdentifier();
    Integer patch_getHeightConstraint();
    Object get_horizontalCollectionSwipeProtector();

    default boolean isHomeFeedOrRelatedVideo() {
        return get_horizontalCollectionSwipeProtector() == null;
    }
    default boolean isSubscriptionOrLibrary() {
        return patch_getHeightConstraint() == null;
    }
}
