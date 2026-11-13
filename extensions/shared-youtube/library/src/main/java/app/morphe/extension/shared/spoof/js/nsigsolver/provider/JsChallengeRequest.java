package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

@SuppressWarnings("unused")
public record JsChallengeRequest(JsChallengeType type, ChallengeInput input, String videoId) {

    public JsChallengeRequest(JsChallengeType type, ChallengeInput input) {
        this(type, input, null);
    }
}
