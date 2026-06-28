package app.morphe.extension.shared.patches.components;

import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Lazy loaded ASCII strings, as found in a byte buffer.
 */
public class BufferAsciiStrings {
    private final byte[] bytes;
    @Nullable
    private String strings;

    public BufferAsciiStrings(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes);
    }

    public String getStrings() {
        String ascii = strings;
        if (ascii == null) {
            strings = ascii = findAsciiStrings(bytes);
        }
        return ascii;
    }

    /**
     * Search through a byte array for all ASCII strings.
     */
    private static String findAsciiStrings(byte[] buffer) {
        StringBuilder builder = new StringBuilder(buffer.length);

        // Valid ASCII values (ignore control characters).
        final int minimumAscii = 32;  // 32 = space character
        final int maximumAscii = 126; // 127 = delete character
        final int minimumAsciiStringLength = 4; // Minimum length of an ASCII string to include.
        // Logger ignores text past 4096 bytes on each line. Must wrap lines otherwise logging is clipped.
        final int preferredLineLength = 3000; // Preferred length before wrapping on next substring.
        final int maxLineLength = 3300; // Hard limit to line wrap in the middle of substring.
        String delimitingCharacter = "❙"; // Non ascii character, to allow easier log filtering.

        final int length = buffer.length;
        final int lastIndex = length - 1;
        int start = 0;
        int currentLineLength = 0;

        for (int end = 0; end < length; end++) {
            final int value = buffer[end];
            final boolean isAscii = (value >= minimumAscii && value <= maximumAscii);
            final boolean atEnd = (end == lastIndex);

            if (!isAscii || atEnd) {
                int wordEnd = end + ((atEnd && isAscii) ? 1 : 0);

                if (wordEnd - start >= minimumAsciiStringLength) {
                    for (int i = start; i < wordEnd; i++) {
                        builder.append((char) buffer[i]);
                        currentLineLength++;

                        // Hard line limit. Hard wrap the current substring to next logger line.
                        if (currentLineLength >= maxLineLength) {
                            builder.append('\n');
                            currentLineLength = 0;
                        }
                    }

                    // Wrap after substring if over preferred limit.
                    if (currentLineLength >= preferredLineLength) {
                        builder.append('\n');
                        currentLineLength = 0;
                    }

                    builder.append(delimitingCharacter);
                    currentLineLength++;
                }

                start = end + 1;
            }
        }

        return builder.toString();
    }
}
