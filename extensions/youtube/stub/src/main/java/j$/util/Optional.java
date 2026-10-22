package j$.util;

public final class Optional<T> {

    /**
     * Returns an {@code Optional} describing the given non-{@code null}
     * value.
     *
     * @param value the value to describe, which must be non-{@code null}
     * @param <T> the type of the value
     * @return an {@code Optional} with the value present
     * @throws NullPointerException if value is {@code null}
     */
    public static <T> Optional<T> of(T value) {
        return null;
    }

    /**
     * Returns an empty {@code Optional} instance.
     *
     * @param <T> the type of the non-existent value
     * @return an empty {@code Optional}
     */
    public static <T> Optional<T> empty() {
        return null;
    }

}