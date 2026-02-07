package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

public class JsChallengeResponse {
    private final JsChallengeType type;
    private final ChallengeOutput output;

    public JsChallengeResponse(JsChallengeType type, ChallengeOutput output) {
        this.type = type;
        this.output = output;
    }

    public JsChallengeType getType() {
        return type;
    }

    public ChallengeOutput getOutput() {
        return output;
    }
}