/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5227
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.icu.text.CompactDecimalFormat;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

/**
 * Shortened numbers that YouTube shows, such as the view counts "4.7M" and "4,7 Mln".
 */
public final class CompactNumbers {

    /**
     * A number, such as 457, 4,7 or 1 234. Some languages show words before it.
     */
    public static final Pattern NUMBER_PATTERN =
            Pattern.compile("\\p{Nd}+(?:[.,'’٫٬\u00A0\u202F ]\\p{Nd}+)*");

    /** Powers of ten that units are found for, from a thousand up to a quadrillion. */
    private static final long FIRST_UNIT_VALUE = 1_000;
    private static final int UNIT_VALUES = 13;

    /** Digits after a separator of thousands, such as the 234 of 1,234. */
    private static final int GROUP_DIGITS = 3;

    /**
     * A shortened number unit, such as M.
     *
     * @param value The value of the unit, such as 1000000.
     */
    public record Unit(String text, long value) {
    }

    /** Units of {@link #unitsLocale}, longest first. */
    @GuardedBy("CompactNumbers.class")
    @Nullable
    private static List<Unit> units;
    @GuardedBy("CompactNumbers.class")
    @Nullable
    private static Locale unitsLocale;

    private CompactNumbers() {
    }

    /**
     * @return Shortened number units of the language, such as K and M, longest first.
     */
    public static synchronized List<Unit> getUnits(Locale locale) {
        if (units == null || !locale.equals(unitsLocale)) {
            Map<String, Long> found = new LinkedHashMap<>();
            // Short units, such as M, and long units, such as million.
            for (CompactDecimalFormat.CompactStyle style : CompactDecimalFormat.CompactStyle.values()) {
                try {
                    CompactDecimalFormat format = CompactDecimalFormat.getInstance(locale, style);
                    long value = FIRST_UNIT_VALUE;
                    for (int i = 0; i < UNIT_VALUES; i++) {
                        String formatted = format.format(value);
                        // Units of more words keep the space between them, such as thousand crore.
                        String unit = formatted.replaceAll("[\\p{Nd}.,'’]", "")
                                .replaceAll("[\\s\u00A0\u202F]+", " ").trim();
                        if (!unit.isEmpty() && !found.containsKey(unit)) {
                            // The first value of a unit is shown as 1, such as 1M, or as 10 or 100
                            // in languages with units of ten thousand, such as 10 of the unit of ten thousand.
                            Long shown = parseDigits(formatted);
                            if (shown != null && shown > 0) {
                                found.put(unit, value / shown);
                            }
                        }
                        value *= 10;
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "getUnits failure", ex);
                }
            }
            // The app can be in another language than its locale.
            found.putIfAbsent("K", 1_000L);
            found.putIfAbsent("M", 1_000_000L);
            found.putIfAbsent("B", 1_000_000_000L);
            // English (India) shows lakh and crore, but abbreviates them as L and Cr.
            found.putIfAbsent("lakh", 100_000L);
            found.putIfAbsent("crore", 10_000_000L);

            List<Unit> list = new ArrayList<>(found.size());
            found.forEach((text, value) -> list.add(new Unit(text, value)));
            list.sort((a, b) -> b.text.length() - a.text.length());
            units = Collections.unmodifiableList(list);
            unitsLocale = locale;
        }
        return units;
    }

    /**
     * @return The number of the digits of the text, ignoring all other characters, or null if there are none.
     */
    @Nullable
    private static Long parseDigits(String text) {
        long number = 0;
        boolean found = false;
        for (int i = 0, length = text.length(); i < length; ) {
            final int codePoint = text.codePointAt(i);
            final int digit = Character.digit(codePoint, 10);
            if (digit >= 0) {
                number = number * 10 + digit;
                found = true;
            }
            i += Character.charCount(codePoint);
        }
        return found ? number : null;
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c)
                || Character.getType(c) == Character.FORMAT;
    }

    /**
     * @return If the text has a character between the indexes that is not a space or a mark of the text direction.
     */
    private static boolean hasText(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!isSeparator(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param hasUnit If a unit follows the number. Then a separator followed by fewer or more digits
     *                than a group of thousands is the decimal separator, such as of 4,7 Mln.
     *                Numbers without a unit show no decimals, such as 1,234.
     */
    private static double parseNumber(String number, boolean hasUnit) {
        int decimalIndex = -1;
        if (hasUnit) {
            for (int i = number.length() - 1; i >= 0; i--) {
                final char c = number.charAt(i);
                if (Character.isDigit(c)) {
                    continue;
                }
                if ((c == '.' || c == ',' || c == '٫') && number.length() - 1 - i != GROUP_DIGITS) {
                    decimalIndex = i;
                }
                break;
            }
        }

        StringBuilder digits = new StringBuilder();
        for (int i = 0, length = number.length(); i < length; ) {
            final int codePoint = number.codePointAt(i);
            final int digit = Character.digit(codePoint, 10);
            if (digit >= 0) {
                digits.append((char) ('0' + digit));
            } else if (i == decimalIndex) {
                digits.append('.');
            }
            i += Character.charCount(codePoint);
        }
        return Double.parseDouble(digits.toString());
    }

    /**
     * @param text A shortened number with nothing else, such as "4.7M", "4,7 Mln" or "430".
     * @return The number, such as 4700000, or null if the text is not a number with a known unit.
     */
    @Nullable
    public static Long parse(String text, Locale locale) {
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (!matcher.find() || hasText(text, 0, matcher.start())) {
            return null;
        }
        final int length = text.length();
        int index = matcher.end();
        while (index < length && isSeparator(text.charAt(index))) {
            index++;
        }

        Unit found = null;
        if (index < length) {
            for (Unit unit : getUnits(locale)) {
                if (text.regionMatches(true, index, unit.text, 0, unit.text.length())) {
                    found = unit;
                    index += unit.text.length();
                    break;
                }
            }
            if (found == null) {
                return null;
            }
            // The units are without the period of abbreviations, such as of Mio.
            if (index < length && text.charAt(index) == '.') {
                index++;
            }
        }
        if (hasText(text, index, length)) {
            return null;
        }

        if (found == null) {
            return Math.round(parseNumber(matcher.group(), false));
        }
        return Math.round(parseNumber(matcher.group(), true) * found.value);
    }
}
