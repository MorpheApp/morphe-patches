/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.components;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public final class LithoFilterPatch {

    /**
     * Simple wrapper to pass the litho parameters through the prefix search.
     */
    private record LithoFilterParameters(ContextInterface contextInterface, String identifier,
                                         String path, String accessibility, byte[] buffer,
                                         BufferAsciiStrings asciiStrings) {
        @NonNull
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder(identifier.length()
                    + path.length()
                    + accessibility.length()
                    + buffer.length);

            builder.append("ID: ");
            builder.append(identifier);
            if (!accessibility.isEmpty()) {
                // AccessibilityId and AccessibilityText are pieces of BufferStrings.
                builder.append(" Accessibility: ");
                builder.append(accessibility);
            }
            builder.append(" Path: ");
            builder.append(path);
            if (SharedYouTubeSettings.DEBUG_PROTOBUFFER.get()) {
                builder.append(" BufferStrings: ");
                builder.append(asciiStrings.getStrings());
            }

            return builder.toString();
        }
    }

    /**
     * Placeholder for actual filters.
     */
    private static final class DummyFilter extends Filter { }

    private static final Filter[] filters = new Filter[] {
            new DummyFilter() // Replaced during patching, do not touch.
    };

    /**
     * Litho layout fixed thread pool size override.
     * <p>
     * Unpatched YouTube uses a layout fixed thread pool between 1 and 3 threads:
     * <pre>
     * 1 thread - > Device has less than 6 cores
     * 2 threads -> Device has over 6 cores and less than 6GB of memory
     * 3 threads -> Device has over 6 cores and more than 6GB of memory
     * </pre>
     *
     * Using more than 1 thread causes layout issues such as the 'You' tab watch/playlist shelf
     * that is sometimes incorrectly hidden (Morphe is not hiding it), and seems to
     * fix a race issue if using the active navigation tab status with litho filtering.
     */
    private static final int LITHO_LAYOUT_THREAD_POOL_SIZE = 1;

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    /**
     * Because litho filtering is multithreaded and the buffer is passed in from a different injection point,
     * the buffer is saved to a ThreadLocal so each calling thread does not interfere with other threads.
     * Used for 20.21 and lower.
     */
    private static final ThreadLocal<byte[]> bufferThreadLocal = new ThreadLocal<>();

    private static final StringTrieSearch contextSearchTree = new StringTrieSearch();
    private static final StringTrieSearch pathSearchTree = new StringTrieSearch();
    private static final StringTrieSearch identifierSearchTree = new StringTrieSearch();

    static {

        for (Filter filter : filters) {
            filterUsingCallbacks(identifierSearchTree, filter,
                    filter.identifierCallbacks, Filter.FilterContentType.IDENTIFIER);
            filterUsingCallbacks(pathSearchTree, filter,
                    filter.pathCallbacks, Filter.FilterContentType.PATH);
        }

        Logger.printDebug(() -> "Using: "
                + identifierSearchTree.numberOfPatterns() + " identifier filters"
                + " (" + identifierSearchTree.getEstimatedMemorySize() + " KB), "
                + pathSearchTree.numberOfPatterns() + " path filters"
                + " (" + pathSearchTree.getEstimatedMemorySize() + " KB)");
    }

    private static void filterUsingCallbacks(StringTrieSearch pathSearchTree,
                                             Filter filter, List<StringFilterGroup> groups,
                                             Filter.FilterContentType type) {
        String filterSimpleName = filter.getClass().getSimpleName();

        for (StringFilterGroup group : groups) {
            if (!group.includeInSearch()) {
                continue;
            }

            for (String pattern : group.filters) {
                pathSearchTree.addPattern(pattern, (textSearched, matchedStartIndex,
                                                    matchedLength, callbackParameter) -> {
                            if (!group.isEnabled()) return false;

                            LithoFilterParameters parameters = (LithoFilterParameters) callbackParameter;
                            final boolean isFiltered = filter.isFiltered(parameters.contextInterface,
                                    parameters.identifier, parameters.accessibility, parameters.path,
                                    parameters.buffer, parameters.asciiStrings,
                                    group, type, matchedStartIndex);

                            if (isFiltered && BaseSettings.DEBUG.get()) {
                                Logger.printDebug(() -> type == Filter.FilterContentType.IDENTIFIER
                                        ? filterSimpleName + " filtered identifier: " + parameters.identifier
                                        : filterSimpleName + " filtered path: " + parameters.path);
                            }

                            return isFiltered;
                        }
                );
            }
        }
    }

    /**
     * Injection point.  Called off the main thread.
     * Targets 20.21 and lower.
     */
    public static void setProtoBuffer(@Nullable ByteBuffer buffer) {
        if (buffer == null || !buffer.hasArray()) {
            // It appears the buffer can be cleared out just before the call to #filter()
            // Ignore this null value and retain the last buffer that was set.
            Logger.printDebug(() -> "Ignoring null or empty buffer: " + buffer);
        } else {
            // Set the buffer to a thread local.  The buffer will remain in memory, even after the call to #filter completes.
            // This is intentional, as it appears the buffer can be set once and then filtered multiple times.
            // The buffer will be cleared from memory after a new buffer is set by the same thread,
            // or when the calling thread eventually dies.
            bufferThreadLocal.set(buffer.array());
        }
    }

    /**
     * Injection point.
     */
    public static boolean isFiltered(ContextInterface contextInterface, @Nullable byte[] bytes,
                                     @Nullable String accessibilityId, @Nullable String accessibilityText) {
        try {
            String identifier = contextInterface.patch_getIdentifier();
            StringBuilder pathBuilder = contextInterface.patch_getPathBuilder();
            //noinspection SizeReplaceableByIsEmpty
            if (identifier.isEmpty() || pathBuilder.length() == 0) {
                return false;
            }

            String path = pathBuilder.toString();

            String accessibility;
            if (accessibilityText != null && !accessibilityText.isBlank()) {
                accessibility = accessibilityId + '|' + accessibilityText;
            } else if (accessibilityId != null && !accessibilityId.isBlank()) {
                accessibility = accessibilityId;
            } else {
                accessibility = "";
            }

            byte[] buffer = is_20_22_or_greater()
                    ? bytes
                    : bufferThreadLocal.get();
            // Potentially the buffer may have been null or never set up until now.
            // Use an empty buffer so the litho id/path filters that do not use a buffer still work.
            if (buffer == null) {
                buffer = EMPTY_BYTE_ARRAY;
            }

            LithoFilterParameters parameter = new LithoFilterParameters(
                    contextInterface, identifier, path, accessibility,
                    buffer, new BufferAsciiStrings(buffer));
            Logger.printDebug(() -> "Searching " + parameter);

            return identifierSearchTree.matches(identifier, parameter)
                    || pathSearchTree.matches(path, parameter);
        } catch (Exception ex) {
            Logger.printException(() -> "isFiltered failure", ex);
        }

        return false;
    }

    private static boolean is_20_22_or_greater() {
        return Utils.getAppVersionName().compareTo("20.22.00") >= 0;
    }

    /**
     * Injection point.
     */
    public static int getExecutorCorePoolSize(int originalCorePoolSize) {
        if (originalCorePoolSize != LITHO_LAYOUT_THREAD_POOL_SIZE) {
            Logger.printDebug(() -> "Overriding core thread pool size from: " + originalCorePoolSize
                    + " to: " + LITHO_LAYOUT_THREAD_POOL_SIZE);
        }

        return LITHO_LAYOUT_THREAD_POOL_SIZE;
    }

    /**
     * Injection point.
     */
    public static int getExecutorMaxThreads(int originalMaxThreads) {
        if (originalMaxThreads != LITHO_LAYOUT_THREAD_POOL_SIZE) {
            Logger.printDebug(() -> "Overriding max thread pool size from: " + originalMaxThreads
                    + " to: " + LITHO_LAYOUT_THREAD_POOL_SIZE);
        }

        return LITHO_LAYOUT_THREAD_POOL_SIZE;
    }
}
