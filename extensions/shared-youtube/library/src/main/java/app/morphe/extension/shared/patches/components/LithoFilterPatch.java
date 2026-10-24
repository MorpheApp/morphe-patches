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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
                                         CharSequence path, String accessibility, byte[] buffer,
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
     * Interface added to the decorator that holds the item of a list, such as the feed.
     */
    public interface ListItemDecoratorInterface {
        @Nullable
        Object patch_getListItem();
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

    /**
     * Observable lists that hold the items of the lists, such as the feed.
     * The lists use the equality of their content, so they are kept by reference instead of in a set.
     */
    private static final List<WeakReference<List<?>>> observableLists = new ArrayList<>();

    /**
     * Conversion context of the element that the calling thread is parsing.
     */
    private static final ThreadLocal<WeakReference<ContextInterface>> parsedElementContext = new ThreadLocal<>();

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

            for (CharSequence pattern : group.filters) {
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
            if (identifier == null || identifier.isEmpty() || pathBuilder.length() == 0) {
                return false;
            }

            String accessibility;
            if (accessibilityText != null && !accessibilityText.isBlank()) {
                accessibility = accessibilityId + '|' + accessibilityText;
            } else if (accessibilityId != null && !accessibilityId.isBlank()) {
                accessibility = accessibilityId;
            } else {
                accessibility = "";
            }

            byte[] buffer = useLegacyLithoFiltering()
                    ? bufferThreadLocal.get()
                    : bytes;
            // Potentially the buffer may have been null or never set up until now.
            // Use an empty buffer so the litho id/path filters that do not use a buffer still work.
            if (buffer == null) {
                buffer = EMPTY_BYTE_ARRAY;
            }

            LithoFilterParameters parameter = new LithoFilterParameters(
                    contextInterface, identifier, pathBuilder, accessibility,
                    buffer, new BufferAsciiStrings(buffer));
            Logger.printDebug(() -> "Searching " + parameter);

            if (identifierSearchTree.matches(identifier, parameter)
                    || pathSearchTree.matches(pathBuilder, parameter)) {
                // The root component of the element, such as 'identifier|CellType|',
                // means that the whole element is hidden.
                final int identifierLength = identifier.length();
                if (pathBuilder.length() > identifierLength + 1
                        && pathBuilder.charAt(identifierLength) == '|'
                        && pathBuilder.indexOf("|", identifierLength + 1) == pathBuilder.length() - 1
                        && pathBuilder.indexOf(identifier) == 0) {
                    removeListItem(contextInterface);
                }
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "isFiltered failure", ex);
        }

        return false;
    }

    /**
     * Injection point.
     * Called when an observable list, which can hold the items of a list such as the feed, is created.
     */
    public static void addObservableList(List<?> list) {
        synchronized (observableLists) {
            // Remove the lists that no longer exist.
            observableLists.removeIf(reference -> reference.get() == null);
            observableLists.add(new WeakReference<>(list));
        }
    }

    /**
     * Injection point.
     * Called when the parsing of an element starts.
     */
    public static void setParsedElementContext(@Nullable ContextInterface contextInterface) {
        parsedElementContext.set(contextInterface == null ? null : new WeakReference<>(contextInterface));
    }

    /**
     * Injection point.
     * Called when the element that is being parsed is hidden.
     */
    public static void removeParsedElementListItem() {
        WeakReference<ContextInterface> reference = parsedElementContext.get();
        ContextInterface contextInterface = reference == null ? null : reference.get();
        if (contextInterface != null) {
            removeListItem(contextInterface);
        }
    }

    /**
     * Called when the whole element is hidden.
     * <p>
     * Hiding an element replaces it with an empty component, but the item of the element stays
     * in its list. The grids, such as the feed of the tablet layout, then show an empty cell
     * in its place. To prevent this, the item is removed from the list.
     */
    private static void removeListItem(ContextInterface contextInterface) {
        try {
            List<?> decorators = contextInterface.patch_getCommandEventDataDecorators();
            if (decorators == null) {
                return;
            }

            for (Object decorator : decorators) {
                if (!(decorator instanceof ListItemDecoratorInterface listItemDecorator)) {
                    continue;
                }

                Object item = listItemDecorator.patch_getListItem();
                if (item == null) {
                    continue;
                }

                // The lists can only be changed on the main thread.
                Utils.runOnMainThread(() -> {
                    try {
                        synchronized (observableLists) {
                            Iterator<WeakReference<List<?>>> iterator = observableLists.iterator();
                            while (iterator.hasNext()) {
                                List<?> list = iterator.next().get();
                                if (list == null) {
                                    iterator.remove();
                                    continue;
                                }

                                final int index = list.indexOf(item);
                                if (index >= 0) {
                                    list.remove(index);
                                    Logger.printDebug(() -> "Removed list item of filtered element: " + item);
                                    return;
                                }
                            }
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "removeListItem failure", ex);
                    }
                });
            }
        } catch (Exception ex) {
            Logger.printException(() -> "removeListItem failure", ex);
        }
    }

    private static boolean useLegacyLithoFiltering() {
        return false; // Method is modified during patching.
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
