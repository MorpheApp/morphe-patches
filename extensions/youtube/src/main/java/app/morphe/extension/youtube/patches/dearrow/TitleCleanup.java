/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4959
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import static app.morphe.extension.youtube.patches.dearrow.TitleCleanupData.FANCY_TEXT;
import static app.morphe.extension.youtube.patches.dearrow.TitleCleanupData.KEEP_CASE;
import static app.morphe.extension.youtube.patches.dearrow.TitleCleanupData.KEEP_CASE_PREFIXES;
import static app.morphe.extension.youtube.patches.dearrow.TitleCleanupData.NOT_ACRONYMS;
import static app.morphe.extension.youtube.patches.dearrow.TitleCleanupData.NOT_SENTENCE_ENDS;
import static app.morphe.extension.youtube.patches.dearrow.TitleCleanupData.TITLE_CASE_LOWERCASE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans up the video titles that are not DeArrow titles, as titles submitted to DeArrow are never changed.
 * <p>
 * Words written in capitals are changed to the case of the rest of the title, except acronyms
 * and the words of the word lists. Emojis are removed, fancy text such as math bold
 * or circled letters is changed to plain letters, and repeated and trailing exclamation marks
 * are removed. Words that are not written in capitals are not changed, so titles in languages
 * with other capitalization rules are not broken.
 * <p>
 * The word lists and the fancy text characters are in {@link TitleCleanupData}.
 */
public final class TitleCleanup {

    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * Acronyms written with dots, such as 'U.S.A' (without the last dot).
     */
    private static final Pattern DOTTED_ACRONYM_PATTERN = Pattern.compile("(?:\\p{L}\\.)+\\p{L}?");

    private static final Pattern ROMAN_NUMERAL_PATTERN = Pattern.compile("X{0,3}(?:IX|IV|V?I{0,3})");

    /**
     * Ordinal numbers written in capitals, such as '1ST'.
     */
    private static final Pattern ORDINAL_PATTERN = Pattern.compile("\\d+(?:ST|ND|RD|TH)");

    private static final Pattern REPEATED_MARKS_PATTERN = Pattern.compile("[!?]{2,}");

    private static final Pattern EMPTY_BRACKETS_PATTERN = Pattern.compile("\\(\\s*\\)|\\[\\s*\\]|\\{\\s*\\}|【\\s*】");

    /**
     * Letters that are only in Turkish and Azerbaijani, which change the case of 'i' differently.
     */
    private static final Pattern TURKISH_LETTERS_PATTERN = Pattern.compile("[İı]");

    private static final Locale TURKISH_LOCALE = Locale.forLanguageTag("tr");

    /**
     * Characters of a word that is only a separator, such as 'Title - Channel'.
     */
    private static final String SEPARATOR_CHARACTERS = "-–—|•·~:/";

    /**
     * Last characters of a word that ends a sentence.
     */
    private static final String SENTENCE_END_CHARACTERS = ".!?:]】";

    private static final int VARIATION_SELECTOR_TEXT = 0xFE0E;
    private static final int VARIATION_SELECTOR_EMOJI = 0xFE0F;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int COMBINING_KEYCAP = 0x20E3;

    /**
     * @return The cleaned up title, or the same title if nothing is cleaned up
     *         or if the title is only emojis.
     */
    public static String cleanUpTitle(String title) {
        String text = removeEmojis(replaceFancyText(title));
        text = EMPTY_BRACKETS_PATTERN.matcher(text).replaceAll(" ");

        List<String> words = new ArrayList<>(Arrays.asList(WHITESPACE_PATTERN.split(text.trim())));
        while (!words.isEmpty() && isSeparator(words.get(0))) {
            words.remove(0);
        }
        while (!words.isEmpty() && isSeparator(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        if (!hasLetterOrDigit(words)) {
            return title;
        }

        fixCapitals(words);
        return cleanUpPunctuation(String.join(" ", words));
    }

    // region Capitals

    private static void fixCapitals(List<String> words) {
        // Words with at least two letters, and the ones written in capitals.
        int casedWords = 0;
        int capitalWords = 0;
        for (String word : words) {
            final int[] cases = letterCases(word);
            if (cases[0] + cases[1] >= 2) {
                casedWords++;
                if (cases[1] == 0) {
                    capitalWords++;
                }
            }
        }
        if (capitalWords == 0) {
            return;
        }

        // If most words are in capitals, then short words in capitals are not acronyms.
        final boolean mostlyCapitals = capitalWords * 2 > casedWords;
        final boolean titleCase = isTitleCase(words);
        final Locale locale = TURKISH_LETTERS_PATTERN.matcher(String.join(" ", words)).find()
                ? TURKISH_LOCALE
                : Locale.ROOT;

        List<String> original = new ArrayList<>(words);
        final int lastIndex = words.size() - 1;
        for (int i = 0; i <= lastIndex; i++) {
            String word = original.get(i);
            final int[] cases = letterCases(word);
            if (cases[0] == 0 || cases[1] > 0 || keepsCase(word)) {
                continue;
            }
            if (ORDINAL_PATTERN.matcher(trimToLettersAndDigits(word)).matches()) {
                words.set(i, word.toLowerCase(locale));
                continue;
            }

            final boolean sentenceStart = i == 0 || endsSentence(original.get(i - 1));
            // Each part of a compound word is changed separately, such as 'T-SHIRT' or 'USB-C'.
            StringBuilder builder = new StringBuilder(word.length());
            int partStart = 0;
            for (int j = 0, length = word.length(); j <= length; j++) {
                if (j < length && word.charAt(j) != '-' && word.charAt(j) != '/') {
                    continue;
                }
                builder.append(fixCapitalsOfPart(word.substring(partStart, j),
                        sentenceStart && partStart == 0, i == lastIndex, mostlyCapitals, titleCase, locale));
                if (j < length) {
                    builder.append(word.charAt(j));
                }
                partStart = j + 1;
            }
            words.set(i, builder.toString());
        }
    }

    private static String fixCapitalsOfPart(String part, boolean sentenceStart, boolean lastWord,
                                            boolean mostlyCapitals, boolean titleCase, Locale locale) {
        final int[] cases = letterCases(part);
        final int letters = cases[0] + cases[1];
        if (letters == 0 || cases[1] > 0) {
            return part;
        }

        String core = trimToLettersAndDigits(part);
        String lowercase = core.toLowerCase(locale);
        if (lowercase.equals("i") || lowercase.startsWith("i'") || lowercase.startsWith("i’")) {
            return capitalize(part, locale); // I, I'm, I'll
        }
        if (letters == 1 && !mostlyCapitals) {
            return part; // Plan A
        }
        if (core.length() >= 2 && ROMAN_NUMERAL_PATTERN.matcher(core).matches()) {
            return part;
        }
        if (!mostlyCapitals && letters <= 3 && letters == core.length()
                && !NOT_ACRONYMS.contains(lowercase) && !TITLE_CASE_LOWERCASE.contains(lowercase)) {
            return part; // Acronym
        }
        if (sentenceStart) {
            return capitalize(part, locale);
        }
        if (titleCase && (lastWord || !TITLE_CASE_LOWERCASE.contains(lowercase))) {
            return capitalize(part, locale);
        }
        return part.toLowerCase(locale);
    }

    private static boolean keepsCase(String word) {
        if (word.startsWith("#") || word.startsWith("@")) {
            return true;
        }
        String core = trimToLettersAndDigits(word);
        if (KEEP_CASE.contains(word) || KEEP_CASE.contains(core)) {
            return true;
        }
        for (String prefix : KEEP_CASE_PREFIXES) {
            if (core.startsWith(prefix)) {
                return true;
            }
        }
        for (int i = 0, length = core.length(); i < length; i++) {
            if (Character.isDigit(core.charAt(i))) {
                // Model names and units, such as 'PS5' or '4K'.
                return !ORDINAL_PATTERN.matcher(core).matches();
            }
        }
        return DOTTED_ACRONYM_PATTERN.matcher(core).matches();
    }

    /**
     * Words in capitals are not used to find the case of the title, as they are the words that are changed.
     *
     * @return If the other words of the title start with a capital, except the short words
     *         that can be lowercase in titles, such as 'Video of the Year'.
     */
    private static boolean isTitleCase(List<String> words) {
        int capitalized = 0;
        for (int i = 1, count = words.size(); i < count; i++) {
            String word = words.get(i);
            final int[] cases = letterCases(word);
            if (cases[0] + cases[1] < 2 || cases[1] == 0 || endsSentence(words.get(i - 1))) {
                continue;
            }
            final int first = word.codePointAt(0);
            if (!Character.isLetter(first)) {
                continue;
            }
            if (Character.isUpperCase(first) || Character.isTitleCase(first)) {
                capitalized++;
            } else if (cases[0] == 0
                    && !TITLE_CASE_LOWERCASE.contains(trimToLettersAndDigits(word).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return capitalized > 0;
    }

    private static boolean endsSentence(String word) {
        if (isSeparator(word)) {
            return true;
        }
        if (SENTENCE_END_CHARACTERS.indexOf(word.charAt(word.length() - 1)) < 0) {
            return false;
        }
        return !KEEP_CASE.contains(word)
                && !NOT_SENTENCE_ENDS.contains(word.toLowerCase(Locale.ROOT))
                && !(word.endsWith(".") && DOTTED_ACRONYM_PATTERN.matcher(trimToLettersAndDigits(word)).matches());
    }

    /**
     * @return The word with only its first letter in capitals.
     */
    private static String capitalize(String word, Locale locale) {
        for (int i = 0, length = word.length(); i < length; ) {
            final int codePoint = word.codePointAt(i);
            final int next = i + Character.charCount(codePoint);
            if (Character.isLetter(codePoint)) {
                return word.substring(0, next) + word.substring(next).toLowerCase(locale);
            }
            i = next;
        }
        return word;
    }

    /**
     * @return The number of uppercase letters and of lowercase letters of the text.
     */
    private static int[] letterCases(String text) {
        int uppercase = 0;
        int lowercase = 0;
        for (int i = 0, length = text.length(); i < length; ) {
            final int codePoint = text.codePointAt(i);
            if (Character.isLowerCase(codePoint)) {
                lowercase++;
            } else if (Character.isUpperCase(codePoint) || Character.isTitleCase(codePoint)) {
                uppercase++;
            }
            i += Character.charCount(codePoint);
        }
        return new int[]{uppercase, lowercase};
    }

    /**
     * @return The text without the characters before its first letter or digit
     *         and after its last letter or digit, such as quotes and brackets.
     */
    private static String trimToLettersAndDigits(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && !Character.isLetterOrDigit(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && !Character.isLetterOrDigit(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    private static boolean isSeparator(String word) {
        for (int i = 0, length = word.length(); i < length; i++) {
            if (SEPARATOR_CHARACTERS.indexOf(word.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasLetterOrDigit(List<String> words) {
        for (String word : words) {
            for (int i = 0, length = word.length(); i < length; ) {
                final int codePoint = word.codePointAt(i);
                if (Character.isLetterOrDigit(codePoint)) {
                    return true;
                }
                i += Character.charCount(codePoint);
            }
        }
        return false;
    }

    // endregion

    // region Punctuation

    /**
     * Repeated '!' and '?' are changed to one, and the '!' at the end are removed.
     * A '?' at the end is kept, such as 'Is it real?!!' changed to 'Is it real?'.
     */
    private static String cleanUpPunctuation(String text) {
        Matcher matcher = REPEATED_MARKS_PATTERN.matcher(text);
        if (matcher.find()) {
            StringBuffer buffer = new StringBuffer(text.length());
            do {
                matcher.appendReplacement(buffer, matcher.group().indexOf('?') >= 0 ? "?" : "!");
            } while (matcher.find());
            matcher.appendTail(buffer);
            text = buffer.toString();
        }

        int end = text.length();
        boolean question = false;
        while (end > 0) {
            final char c = text.charAt(end - 1);
            if (c == '?') {
                question = true;
            } else if (c != '!' && c != ' ') {
                break;
            }
            end--;
        }
        if (end == text.length() || end == 0) {
            return text;
        }
        if (KEEP_CASE.contains(text.substring(text.lastIndexOf(' ') + 1))) {
            return text; // Words that end with '!', such as 'osu!'.
        }
        return text.substring(0, end) + (question ? "?" : "");
    }

    // endregion

    // region Fancy text and emojis

    /**
     * Changes the fancy text characters to plain letters and numbers, such as math bold,
     * circled, fullwidth and small capital letters.
     */
    private static String replaceFancyText(String text) {
        StringBuilder builder = null;
        for (int i = 0, length = text.length(); i < length; ) {
            final int codePoint = text.codePointAt(i);
            String plain = FANCY_TEXT.get(codePoint);
            if (plain != null && builder == null) {
                builder = new StringBuilder(length);
                builder.append(text, 0, i);
            }
            if (builder != null) {
                if (plain != null) {
                    builder.append(plain);
                } else {
                    builder.appendCodePoint(codePoint);
                }
            }
            i += Character.charCount(codePoint);
        }
        return builder == null ? text : builder.toString();
    }

    /**
     * Removes the emojis, with their skin tones and joined emojis. An emoji between two letters
     * is changed to a space. Emojis of punctuation are changed to the punctuation, and keycap
     * emojis to their digit. Flags are not removed.
     */
    private static String removeEmojis(String text) {
        StringBuilder builder = null;
        final int length = text.length();
        for (int i = 0; i < length; ) {
            final int codePoint = text.codePointAt(i);
            int next = i + Character.charCount(codePoint);
            final int following = next < length ? text.codePointAt(next) : 0;

            String replacement;
            if (isKeycapBase(codePoint) && (following == COMBINING_KEYCAP || (following == VARIATION_SELECTOR_EMOJI
                    && next + 1 < length && text.charAt(next + 1) == COMBINING_KEYCAP))) {
                replacement = String.valueOf((char) codePoint);
                next = skipEmojiModifiers(text, next);
            } else if (codePoint == VARIATION_SELECTOR_TEXT || codePoint == VARIATION_SELECTOR_EMOJI) {
                replacement = "";
            } else if (isEmoji(codePoint) || (following == VARIATION_SELECTOR_EMOJI && codePoint >= 0x80)) {
                replacement = emojiPunctuation(codePoint);
                next = skipEmojiModifiers(text, next);
                if (replacement == null) {
                    replacement = builderEndsWithLetterOrDigit(builder, text, i)
                            && next < length && Character.isLetterOrDigit(text.codePointAt(next))
                            ? " "
                            : "";
                }
            } else {
                if (builder != null) {
                    builder.appendCodePoint(codePoint);
                }
                i = next;
                continue;
            }

            if (builder == null) {
                builder = new StringBuilder(text.length());
                builder.append(text, 0, i);
            }
            builder.append(replacement);
            i = next;
        }
        return builder == null ? text : builder.toString();
    }

    private static boolean builderEndsWithLetterOrDigit(StringBuilder builder, String text, int index) {
        CharSequence sequence = builder == null ? text.substring(0, index) : builder;
        final int length = sequence.length();
        return length > 0 && Character.isLetterOrDigit(Character.codePointBefore(sequence, length));
    }

    /**
     * @return The index after the variation selectors, skin tones, keycaps, tags
     *         and the emojis joined with a zero width joiner.
     */
    private static int skipEmojiModifiers(String text, int index) {
        final int length = text.length();
        while (index < length) {
            final int codePoint = text.codePointAt(index);
            final int next = index + Character.charCount(codePoint);
            if (codePoint == VARIATION_SELECTOR_TEXT || codePoint == VARIATION_SELECTOR_EMOJI
                    || codePoint == COMBINING_KEYCAP
                    || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF) // Skin tones
                    || (codePoint >= 0xE0020 && codePoint <= 0xE007F)) { // Tags of subdivision flags
                index = next;
            } else if (codePoint == ZERO_WIDTH_JOINER && next < length) {
                final int joined = text.codePointAt(next);
                if (!isEmoji(joined)) {
                    break;
                }
                index = next + Character.charCount(joined);
            } else {
                break;
            }
        }
        return index;
    }

    private static boolean isKeycapBase(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9') || codePoint == '#' || codePoint == '*';
    }

    private static String emojiPunctuation(int codePoint) {
        return switch (codePoint) {
            case 0x203C -> "!!"; // Double exclamation mark
            case 0x2049 -> "!?"; // Exclamation question mark
            case 0x2753, 0x2754 -> "?"; // Question mark ornaments
            case 0x2755, 0x2757 -> "!"; // Exclamation mark ornaments
            default -> null;
        };
    }

    /**
     * Characters that are emojis without a variation selector. Flags and characters that are emojis
     * only with a variation selector are not included, such as arrows used as separators.
     */
    private static boolean isEmoji(int codePoint) {
        if (codePoint < 0x2000) {
            return false;
        }
        if (codePoint >= 0x1F000) {
            return codePoint <= 0x1F0FF // Mahjong, domino and playing cards
                    || (codePoint >= 0x1F18A && codePoint <= 0x1F1AD) // Squared words, such as 'NEW'
                    || (codePoint >= 0x1F200 && codePoint <= 0x1F6FF) // Pictographs, emoticons and transport
                    || (codePoint >= 0x1F7E0 && codePoint <= 0x1F7FF) // Colored circles and squares
                    || (codePoint >= 0x1F900 && codePoint <= 0x1FAFF); // Supplemental pictographs
        }
        return (codePoint >= 0x2600 && codePoint <= 0x27BF && !(codePoint >= 0x2768 && codePoint <= 0x2775))
                || codePoint == 0x203C || codePoint == 0x2049
                || codePoint == 0x231A || codePoint == 0x231B || codePoint == 0x2328 || codePoint == 0x23CF
                || (codePoint >= 0x23E9 && codePoint <= 0x23FA)
                || (codePoint >= 0x2B05 && codePoint <= 0x2B07) || codePoint == 0x2B1B || codePoint == 0x2B1C
                || codePoint == 0x2B50 || codePoint == 0x2B55
                || codePoint == 0x3030 || codePoint == 0x303D || codePoint == 0x3297 || codePoint == 0x3299;
    }

    // endregion
}
