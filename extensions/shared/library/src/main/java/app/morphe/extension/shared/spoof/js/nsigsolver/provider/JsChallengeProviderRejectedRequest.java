package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

public class JsChallengeProviderRejectedRequest extends ContentProviderError {
    public JsChallengeProviderRejectedRequest(String message) {
        super(message);
    }

    public JsChallengeProviderRejectedRequest(String message, Exception cause) {
        super(message, cause);
    }
}
