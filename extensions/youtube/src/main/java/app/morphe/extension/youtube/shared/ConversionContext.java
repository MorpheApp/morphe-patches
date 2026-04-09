/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
 
package app.morphe.extension.youtube.shared;

public final class ConversionContext {
    public static final String ELEMENT_IDENTIFIER_COMPONENT = "ComponentType";
    public static final String ELEMENT_IDENTIFIER_CONTAINER = "Container";
    public static final String ELEMENT_IDENTIFIER_LAZILY = "LazilyConvertedElement";

    /**
     * Interface to use obfuscated methods.
     */
    public interface ContextInterface {
        // Method is added during patching.
        StringBuilder patch_getPathBuilder();
        String patch_getIdentifier();

        default boolean isHomeFeedOrRelatedVideo() {
            return toString().contains("horizontalCollectionSwipeProtector=null");
        }

        default boolean isSubscriptionOrLibrary() {
            return toString().contains("heightConstraint=null");
        }
    }
}
