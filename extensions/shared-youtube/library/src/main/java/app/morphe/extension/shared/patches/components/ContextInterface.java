package app.morphe.extension.shared.patches.components;

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
