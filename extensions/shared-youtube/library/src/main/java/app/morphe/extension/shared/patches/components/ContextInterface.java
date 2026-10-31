/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1919
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches.components;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Interface to use obfuscated methods.
 */
public interface ContextInterface {
    @Nullable
    String patch_getIdentifier();
    @NonNull
    StringBuilder patch_getPathBuilder();
    @Nullable
    Integer patch_getHeightConstraint();
    @Nullable
    Object patch_getHorizontalCollectionSwipeProtector();

    /**
     * @return The decorators of the command event data. One of them holds the item of the list,
     *         such as the feed, that the element belongs to. Null if the app is not patched for it.
     */
    @Nullable
    default List<?> patch_getCommandEventDataDecorators() {
        return null;
    }

    default boolean isHomeFeedOrRelatedVideo() {
        return patch_getHorizontalCollectionSwipeProtector() == null;
    }
    default boolean isSubscriptionOrLibrary() {
        return patch_getHeightConstraint() == null;
    }
}
