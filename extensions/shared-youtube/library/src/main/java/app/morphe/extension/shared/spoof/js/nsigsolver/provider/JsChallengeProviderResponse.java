package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

public class JsChallengeProviderResponse {
    private final JsChallengeRequest request;
    private final JsChallengeResponse response;
    private final Exception error;

    public JsChallengeProviderResponse(JsChallengeRequest request, JsChallengeResponse response, Exception error) {
        this.request = request;
        this.response = response;
        this.error = error;
    }

    public JsChallengeProviderResponse(JsChallengeRequest request, JsChallengeResponse response) {
        this(request, response, null);
    }

    public JsChallengeProviderResponse(JsChallengeRequest request, Exception error) {
        this(request, null, error);
    }

    public JsChallengeRequest getRequest() {
        return request;
    }

    public JsChallengeResponse getResponse() {
        return response;
    }

    public Exception getError() {
        return error;
    }
}