package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

import java.util.List;

public class SolverOutput {
    private String type;
    private String error;
    private String preprocessed_player;
    private List<ResponseData> responses;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getPreprocessedPlayer() {
        return preprocessed_player;
    }

    public void setPreprocessedPlayer(String preprocessed_player) {
        this.preprocessed_player = preprocessed_player;
    }

    public List<ResponseData> getResponses() {
        return responses;
    }

    public void setResponses(List<ResponseData> responses) {
        this.responses = responses;
    }
}