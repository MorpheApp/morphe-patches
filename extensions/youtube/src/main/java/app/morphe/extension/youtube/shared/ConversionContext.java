package app.morphe.extension.youtube.shared;

public final class ConversionContext {
    /**
     * Interface to use obfuscated methods.
     */
    public interface ContextInterface {
        // Method is added during patching.
        StringBuilder patch_getPathBuilder();
        String patch_getIdentifier();
    }
}
