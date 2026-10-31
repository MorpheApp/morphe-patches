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
     * Interface added to the sections of the lists, such as the feed or the related videos.
     */
    public interface ListSectionInterface {
        /**
         * Removes the item from the section, which lays itself out again without it.
         * The rows of the grids are laid out again too, also when the section is no longer a grid.
         *
         * @return If the section has the item.
         */
        boolean patch_removeListItem(Object item);
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
     * Observable lists that hold the items of the lists, such as the feed.
     * The lists use the equality of their content, so they are kept by reference instead of in a set.
     */
    private static final List<WeakReference<List<?>>> observableLists = new ArrayList<>();

    /**
     * Sections of the lists, such as the feed or the related videos, that hold the items.
     */
    private static final List<WeakReference<ListSectionInterface>> listSections = new ArrayList<>();

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

            // Use an empty buffer if there is none, so the litho id/path filters that do not use a buffer still work.
            byte[] buffer = bytes == null ? EMPTY_BYTE_ARRAY : bytes;

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
                        && Utils.startsWith(pathBuilder, identifier)) {
                    removeFilteredElementListItem(contextInterface);
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
     * Called when a section of a list, such as the feed or the related videos, is created.
     */
    public static void addListSection(ListSectionInterface section) {
        synchronized (listSections) {
            // Remove the sections that no longer exist.
            listSections.removeIf(reference -> reference.get() == null);
            listSections.add(new WeakReference<>(section));
        }
    }

    /**
     * Injection point.
     * Called when a section of a list is released, such as when the list is created again.
     * <p>
     * A released section can share the list of its items with the section that replaces it.
     * Removing an item through the released section would not lay out the rows of the new one
     * again, and the rows that are views of the list would throw on the next access.
     */
    public static void removeListSection(ListSectionInterface section) {
        synchronized (listSections) {
            listSections.removeIf(reference -> reference.get() == section);
        }
    }

    /**
     * Injection point.
     * Called when the whole element is hidden.
     * <p>
     * Hiding an element replaces it with an empty component, but the item of the element stays
     * in its list. The grids of the tablet layout, such as the feed or the related videos,
     * then show an empty cell in its place. To prevent this, the item is removed from the list.
     */
    public static void removeFilteredElementListItem(@Nullable ContextInterface contextInterface) {
        try {
            if (contextInterface == null) {
                return;
            }

            List<?> decorators = contextInterface.patch_getCommandEventDataDecorators();
            if (decorators == null) {
                return;
            }

            for (Object decorator : decorators) {
                if (decorator instanceof ListItemDecoratorInterface listItemDecorator) {
                    Object item = listItemDecorator.patch_getListItem();
                    if (item != null) {
                        // The lists can only be changed on the main thread.
                        Utils.runOnMainThread(() -> {
                            // The sections group their items into the rows of some grids, such as
                            // the related videos, and lay the rows out again. Other grids, such as
                            // the feed, have a list for each item.
                            List<ListSectionInterface> sections = new ArrayList<>();
                            synchronized (listSections) {
                                Iterator<WeakReference<ListSectionInterface>> iterator = listSections.iterator();
                                while (iterator.hasNext()) {
                                    ListSectionInterface section = iterator.next().get();
                                    if (section == null) {
                                        iterator.remove();
                                        continue;
                                    }
                                    sections.add(section);
                                }
                            }

                            // Removing the item notifies the list observers, so it is done outside the lock.
                            for (ListSectionInterface section : sections) {
                                if (section.patch_removeListItem(item)) {
                                    Logger.printDebug(() -> "Removed list item of filtered element: " + item);
                                    return;
                                }
                            }

                            List<?> itemList = null;
                            int itemIndex = -1;
                            synchronized (observableLists) {
                                Iterator<WeakReference<List<?>>> iterator = observableLists.iterator();
                                while (iterator.hasNext()) {
                                    List<?> list = iterator.next().get();
                                    if (list == null) {
                                        iterator.remove();
                                        continue;
                                    }

                                    itemIndex = list.indexOf(item);
                                    if (itemIndex >= 0) {
                                        itemList = list;
                                        break;
                                    }
                                }
                            }

                            // Removing the item notifies the list observers, so it is done outside the lock.
                            if (itemList != null) {
                                itemList.remove(itemIndex);
                                Logger.printDebug(() -> "Removed list item of filtered element: " + item);
                            }
                        });
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "removeFilteredElementListItem failure", ex);
        }
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
