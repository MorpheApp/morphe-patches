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
     * @return {@code true} if a value is present, otherwise {@code false}
     */
    public boolean isPresent() {
        return false;
    }

    /**
     * @return the non-{@code null} value described by this {@code Optional}
     * @throws java.util.NoSuchElementException if no value is present
     */
    public T get() {
        return null;
    }

}