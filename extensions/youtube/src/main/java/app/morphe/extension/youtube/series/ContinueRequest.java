package app.morphe.extension.youtube.series;

/** A network callback and its deadline share one tap, even across navigation cancellation. */
final class ContinueRequest {
    private int generation;
    private boolean waiting;

    int begin() {
        waiting = true;
        return ++generation;
    }

    boolean waiting() {
        return waiting;
    }

    void cancel() {
        waiting = false;
        generation++;
    }

    boolean current(int token) {
        return generation == token;
    }

    boolean claim(int token) {
        if (!waiting || !current(token)) return false;
        waiting = false;
        return true;
    }
}
