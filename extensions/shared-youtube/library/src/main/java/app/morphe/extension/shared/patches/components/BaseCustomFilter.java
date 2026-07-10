/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Allows custom filtering using a path and optionally a proto buffer string.
 *
 * <h5>Optional syntax operators</h5>
 *
 * <pre>
 * <code>^</code> Starts with, where the path must start with a string.
 * Must be the first operator of the custom filter.
 *
 * <code>&</code> Path logic and operator. Any number of additional path strings that must
 * also appear in the path. Order of the substrings does not matter.
 * <code>thumbnails&large</code> means the path must contain both 'thumbnails' and 'large' anywhere.
 * Order does not matter and <code>large&thumbnails</code> is identical in function.
 * For slight performance reasons, it's best to use the rarest path string first as it's
 * the path component that causes the filtering callback to start.
 *
 * <code>#</code> Accessibility string. Any string that must appear inside the accessibility
 * string. This is not common to use because most components do not have an accessibility string.
 *
 * <code>$</code> Buffer string. Any string that must appear inside the protocol buffer.
 * Buffer strings are case-sensitive. May contain Unicode characters, spaces,
 * and any syntax characters as the entire string is matched exactly as-is.
 * </pre>
 *
 * <h5>Examples</h5>
 * <pre>
 * <code>video_action_bar</code>
 * Hides the entire player action bar.
 *
 * <code>video_action_bar#id.video.share.button</code>
 * Hides the player action bar share button.
 *
 * <code>video_action_bar#share.button</code>
 * Functionally identical to example above, since partial pattern matches are allowed.
 *
 * <code>^video_lockup_with_attachment.e&thumbnail.e</code>
 * Hides all thumbnails in feed/search, but shows thumbnails everywhere else such as Shorts,
 * watch history, playlists, etc.
 *
 * <code>^video_lockup_with_attachment.e$Mr. Beast</code>
 * Hides a feed/search video, but only if the buffer contains "Mr. Beast" anywhere
 * (video title, video description, channel name, etc.).
 *
 * <code>^video_lockup_with_attachment.e$- MrBeast -</code>
 * Hides videos by the channel MrBeast (but not videos that just use MrBeast in the title),
 * by using a channel specific string.
 * </pre>
 *
 * <p>Shared abstract base class used by both YouTube and YT Music custom filters. Each app-specific
 * subclass supplies the {@link BooleanSetting} that gates the filter, the {@link StringSetting}
 * that stores the newline-separated filter expressions, and the string resource key used for the
 * "invalid syntax" toast (so localized copy can differ per app).
 */
@SuppressWarnings("unused")
public abstract class BaseCustomFilter extends Filter {

    private final BooleanSetting filterEnabledSetting;
    private final StringSetting filterStringsSetting;
    private final String invalidSyntaxToastKey;

    protected BaseCustomFilter(BooleanSetting filterEnabledSetting,
                               StringSetting filterStringsSetting,
                               String invalidSyntaxToastKey) {
        this.filterEnabledSetting = filterEnabledSetting;
        this.filterStringsSetting = filterStringsSetting;
        this.invalidSyntaxToastKey = invalidSyntaxToastKey;

        Collection<CustomFilterGroup> groups = parseCustomFilterGroups();

        if (!groups.isEmpty()) {
            CustomFilterGroup[] groupsArray = groups.toArray(new CustomFilterGroup[0]);
            Logger.printDebug(() -> "Using Custom filters: " + Arrays.toString(groupsArray));
            addPathCallbacks(groupsArray);
        }
    }

    private void showInvalidSyntaxToast(String expression) {
        Utils.showToastLong(str(invalidSyntaxToastKey, expression));
    }

    private final class CustomFilterGroup extends StringFilterGroup {
        /**
         * Optional character for the path that indicates the custom filter path must match the start.
         * Must be the first character of the expression.
         */
        static final String SYNTAX_STARTS_WITH = "^";

        /**
         * Optional character to indicate multiple path expressions.
         */
        static final String SYNTAX_PATH_AND_SYMBOL = "&";

        /**
         * Optional character that separates the path from an accessibility string pattern.
         */
        static final String SYNTAX_ACCESSIBILITY_SYMBOL = "#";

        /**
         * Optional character that separates the path/accessibility from a proto buffer string pattern.
         */
        static final String SYNTAX_BUFFER_SYMBOL = "$";

        final boolean startsWith;
        final String[] extraPathComponents;
        StringTrieSearch accessibilitySearch;
        ByteTrieSearch bufferSearch;

        CustomFilterGroup(boolean startsWith, String firstComponent, String[] extraPathComponents) {
            super(filterEnabledSetting, firstComponent);
            this.startsWith = startsWith;
            this.extraPathComponents = extraPathComponents;
        }

        void addAccessibilityString(String accessibilityString) {
            if (accessibilitySearch == null) {
                accessibilitySearch = new StringTrieSearch();
            }
            accessibilitySearch.addPattern(accessibilityString);
        }

        void addBufferString(String bufferString) {
            if (bufferSearch == null) {
                bufferSearch = new ByteTrieSearch();
            }
            bufferSearch.addPattern(bufferString.getBytes());
        }

        @NonNull
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("CustomFilterGroup{");
            if (accessibilitySearch != null) {
                builder.append(", accessibility=");
                builder.append(accessibilitySearch.getPatterns());
            }

            builder.append("path=");
            if (startsWith) builder.append(SYNTAX_STARTS_WITH);
            builder.append(filters[0]);
            for (String component : extraPathComponents) {
                builder.append(SYNTAX_PATH_AND_SYMBOL);
                builder.append(component);
            }

            if (bufferSearch != null) {
                String delimitingCharacter = "❙";
                builder.append(", bufferStrings=");
                for (byte[] bufferString : bufferSearch.getPatterns()) {
                    builder.append(new String(bufferString));
                    builder.append(delimitingCharacter);
                }
            }
            builder.append("}");
            return builder.toString();
        }
    }

    /**
     * @return the parsed objects
     */
    @NonNull
    @SuppressWarnings("ConstantConditions")
    private Collection<CustomFilterGroup> parseCustomFilterGroups() {
        String rawCustomFilterText = filterStringsSetting.get();
        if (rawCustomFilterText.isBlank()) {
            return Collections.emptyList();
        }

        // Map key is the full path including optional special characters (^, #, &),
        // any accessibility pattern, and the optional buffer symbol ($),
        // but does not contain any buffer patterns.
        Map<String, CustomFilterGroup> result = new HashMap<>();

        Pattern pattern = Pattern.compile(
                "^(" // Map key group.
                        // Optional starts with.
                        + "(\\Q" + CustomFilterGroup.SYNTAX_STARTS_WITH + "\\E?)"
                        // Path string.
                        + "([^" + Pattern.quote(CustomFilterGroup.SYNTAX_ACCESSIBILITY_SYMBOL
                        + CustomFilterGroup.SYNTAX_BUFFER_SYMBOL) + "]*)"
                        // Optional accessibility string.
                        + "(?:\\Q" + CustomFilterGroup.SYNTAX_ACCESSIBILITY_SYMBOL + "\\E([^"
                        + Pattern.quote(CustomFilterGroup.SYNTAX_BUFFER_SYMBOL) + "]*))?"
                        // Optional buffer symbol.
                        + "(\\Q" + CustomFilterGroup.SYNTAX_BUFFER_SYMBOL + "\\E?)"
                        + ")" // end map key group
                        // Buffer pattern.
                        + "(.*)$"
        );

        for (String expression : rawCustomFilterText.split("\n")) {
            if (expression.isBlank()) continue;

            Matcher matcher = pattern.matcher(expression);
            if (!matcher.matches()) {
                showInvalidSyntaxToast(expression);
                continue;
            }

            final String mapKey = matcher.group(1);
            final boolean pathStartsWith = !matcher.group(2).isEmpty();
            final String path = matcher.group(3);
            final String accessibility = matcher.group(4); // null if not present
            final boolean hasBufferSymbol = !matcher.group(5).isEmpty();
            final String buffer = hasBufferSymbol ? matcher.group(6) : null;

            if (path.isBlank()
                    || path.contains(CustomFilterGroup.SYNTAX_STARTS_WITH)
                    || (accessibility != null && accessibility.isEmpty())
                    || (hasBufferSymbol && (buffer == null || buffer.isEmpty()))) {
                showInvalidSyntaxToast(expression);
                continue;
            }

            // Split path components.
            String[] allComponents = path.split(Pattern.quote(CustomFilterGroup.SYNTAX_PATH_AND_SYMBOL), -1);
            final String firstComponent = allComponents[0];
            final String[] extraComponents = allComponents.length > 1
                    ? Arrays.copyOfRange(allComponents, 1, allComponents.length)
                    : new String[0];

            // Validate no component is blank (e.g. "A&&B")
            boolean blankComponent = false;
            for (String component : allComponents) {
                if (component.isBlank()) {
                    blankComponent = true;
                    break;
                }
            }
            if (blankComponent) {
                showInvalidSyntaxToast(expression);
                continue;
            }

            // Use one group object for all expressions with the same path.
            // This ensures the buffer is searched exactly once
            // when multiple paths are used with different buffer strings.
            CustomFilterGroup group = result.get(mapKey);
            if (group == null) {
                group = new CustomFilterGroup(pathStartsWith, firstComponent, extraComponents);
                result.put(mapKey, group);
            }

            if (accessibility != null) {
                group.addAccessibilityString(accessibility);
            }

            if (buffer != null) {
                group.addBufferString(buffer);
            }
        }

        return result.values();
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        // All callbacks are custom filter groups.
        CustomFilterGroup custom = (CustomFilterGroup) matchedGroup;

        // Check path start requirement.
        if (custom.startsWith && contentIndex != 0) {
            return false;
        }

        // Check for extra path components.
        for (String component : custom.extraPathComponents) {
            if (!path.contains(component)) {
                return false;
            }
        }

        // Check accessibility string if specified.
        if (custom.accessibilitySearch != null && !custom.accessibilitySearch.matches(accessibility)) {
            return false;
        }

        // Check buffer if specified.
        return custom.bufferSearch == null || custom.bufferSearch.matches(buffer); // All custom filter conditions passed.
    }
}
