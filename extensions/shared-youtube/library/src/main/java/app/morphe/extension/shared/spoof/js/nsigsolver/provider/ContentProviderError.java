package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

@SuppressWarnings("unused")
public class ContentProviderError extends Exception {
    public ContentProviderError(String message) {
        super(message);
    }

    public ContentProviderError(String message, Exception cause) {
        super(message, cause);
    }
}
