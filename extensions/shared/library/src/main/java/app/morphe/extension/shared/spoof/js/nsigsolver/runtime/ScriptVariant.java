package app.morphe.extension.shared.spoof.js.nsigsolver.runtime;

public enum ScriptVariant {
    UNKNOWN("unknown"),
    MINIFIED("minified"),
    UNMINIFIED("unminified"),
    DENO_NPM("deno_npm"),
    BUN_NPM("bun_npm"),
    V8_NPM("v8_npm");

    private final String value;

    ScriptVariant(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ScriptVariant fromString(String text) {
        for (ScriptVariant b : ScriptVariant.values()) {
            if (b.value.equalsIgnoreCase(text)) {
                return b;
            }
        }
        return UNKNOWN;
    }
}
