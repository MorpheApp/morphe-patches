package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

import java.util.Map;

public class ChallengeOutput {
    private final Map<String, String> results;

    public ChallengeOutput(Map<String, String> results) {
        this.results = results;
    }

    public Map<String, String> getResults() {
        return results;
    }
}