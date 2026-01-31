package app.morphe.extension.shared.spoof.js.nsigsolver.common;

public class CachedData {
    private final String code;
    private final String version;
    private final String variant;

    public CachedData(String code, String version, String variant) {
        this.code = code;
        this.version = version;
        this.variant = variant;
    }

    public CachedData(String code) {
        this(code, null, null);
    }

    public String getCode() {
        return code;
    }

    public String getVersion() {
        return version;
    }

    public String getVariant() {
        return variant;
    }
}
