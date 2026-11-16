package app.morphe.extension.shared.spoof.js.nsigsolver.common;

@SuppressWarnings("unused")
public record CachedData(String code, String version, String variant) {

    public CachedData(String code) {
        this(code, null, null);
    }
}
