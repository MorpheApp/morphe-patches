package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

public class JsChallengeProviderError extends ContentProviderError {
    public JsChallengeProviderError(String message) {
        super(message);
    }

    public JsChallengeProviderError(String message, Exception cause) {
        super(message, cause);
    }
}
