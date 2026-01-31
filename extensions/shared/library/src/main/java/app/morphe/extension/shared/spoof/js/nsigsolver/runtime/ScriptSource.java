package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

public enum ScriptSource {
    PYPACKAGE("python package"),
    BINARY("binary"),
    CACHE("cache"),
    WEB("web"),
    BUILTIN("builtin");

    private final String value;

    ScriptSource(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}

