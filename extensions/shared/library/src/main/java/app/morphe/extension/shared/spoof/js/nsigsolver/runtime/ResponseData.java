package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

import java.util.Map;

public class ResponseData {
    private String type;
    private String error;
    private Map<String, String> data;

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

    public Map<String, String> getData() {
        return data;
    }

    public void setData(Map<String, String> data) {
        this.data = data;
    }
}