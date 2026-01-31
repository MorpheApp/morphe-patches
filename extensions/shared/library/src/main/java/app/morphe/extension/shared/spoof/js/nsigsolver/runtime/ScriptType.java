package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

public enum ScriptType {
    LIB("lib"),
    CORE("core");

    private final String value;

    ScriptType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
