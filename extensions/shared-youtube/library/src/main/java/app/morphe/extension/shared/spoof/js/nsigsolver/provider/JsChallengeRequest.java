package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

public class JsChallengeRequest {
    private final JsChallengeType type;
    private final ChallengeInput input;
    private final String videoID;

    public JsChallengeRequest(JsChallengeType type, ChallengeInput input, String videoID) {
        this.type = type;
        this.input = input;
        this.videoID = videoID;
    }

    public JsChallengeRequest(JsChallengeType type, ChallengeInput input) {
        this(type, input, null);
    }

    public JsChallengeType getType() {
        return type;
    }

    public ChallengeInput getInput() {
        return input;
    }

    public String getVideoId() {
        return videoID;
    }
}