package app.morphe.extension.shared.spoof.js.nsigsolver.provider;

import java.util.List;

public class ChallengeInput {
    private final List<String> challenges;

    public ChallengeInput(List<String> challenges) {
        this.challenges = challenges;
    }

    public List<String> getChallenges() {
        return challenges;
    }
}