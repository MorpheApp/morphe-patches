package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

@SuppressWarnings("unused")
public record JsChallengeProviderResponse(JsChallengeRequest request, JsChallengeResponse response,
                                          Exception error) {

    public JsChallengeProviderResponse(JsChallengeRequest request, JsChallengeResponse response) {
        this(request, response, null);
    }

    public JsChallengeProviderResponse(JsChallengeRequest request, Exception error) {
        this(request, null, error);
    }
}
