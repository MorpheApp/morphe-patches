package app.morphe.extension.shared.spoof.js.nsigsolver.common;

public class CacheError extends Exception {
    public CacheError(String message) {
        super(message);
    }
    
    public CacheError(String message, Exception cause) {
        super(message, cause);
    }
}
