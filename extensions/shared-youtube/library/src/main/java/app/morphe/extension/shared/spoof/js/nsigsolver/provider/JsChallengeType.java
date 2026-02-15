package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

public enum JsChallengeType {
    N("n"),
    SIG("sig");

    private final String value;

    JsChallengeType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}