/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Lets the user tag playlists and hide the playlists of hidden tags from the Library.
 *
 * <p>Three places of the app are hooked:
 * <ul>
 *     <li>The items of the Library list and grid views, to remove the playlists hidden by the tag filter.</li>
 *     <li>The flyout menu bottom sheet, to add a 'Tags' row to the menu of a playlist.</li>
 *     <li>The sort button of the Library, to add a 'Tags' filter button next to it.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class PlaylistTagsPatch {

    /**
     * Added to the presenter of the Library list view by the patch.
     */
    public interface LibraryPresenter {
        /**
         * Binds the Library renderer the presenter holds again, which filters the items again.
         */
        void patch_reloadLibrary();
    }

    /**
     * Added to the presenter of the Library grid view by the patch.
     */
    public interface LibraryGridPresenter {
        /**
         * Replaces the rows of the grid with the items.
         */
        void patch_rebindItems(List<Object> items);
    }

    /**
     * The unfiltered items of a grid presenter. The renderer the presenter holds is only the
     * last page it loaded, so the grid is rebuilt from the items of all pages instead.
     */
    private static final class GridItems {
        final List<Object> items = new ArrayList<>();
        /**
         * Set when the presenter cleared its rows, so the next items replace the kept items.
         */
        boolean resetPending;
    }

    private static final String MENU_ROW_TAG = "morphe_playlist_tags_menu_row";
    private static final String FILTER_BUTTON_TAG = "morphe_playlist_tags_filter_button";

    /**
     * Presenters of the Library list and grid views.
     */
    private static final List<WeakReference<Object>> libraryPresenters = new ArrayList<>();
    private static final Map<Object, GridItems> gridItems = new WeakHashMap<>();
    /**
     * The grid presenter that is building items, set before it builds them on the main thread.
     */
    private static WeakReference<Object> buildingGridPresenter = new WeakReference<>(null);

    /**
     * GeneratedMessageLite.toByteArray() of each proto class. The method name is not obfuscated.
     */
    private static final Map<Class<?>, Method> toByteArrayMethods = new ConcurrentHashMap<>();

    /**
     * Field holding the proto of each item wrapper class, and the wrapper classes holding none.
     */
    private static final Map<Class<?>, Field> wrappedMessageFields = new ConcurrentHashMap<>();
    private static final Set<Class<?>> classesWithoutMessage = ConcurrentHashMap.newKeySet();

    /**
     * Filter buttons currently shown, so their text can be updated after the filter changes.
     */
    private static final List<WeakReference<TextView>> filterButtons = new ArrayList<>();

    private PlaylistTagsPatch() {
    }

    private static boolean isEnabled() {
        return Settings.PLAYLIST_TAGS.get();
    }

    private static boolean isMessage(Class<?> type) {
        if (toByteArrayMethods.containsKey(type)) return true;
        try {
            toByteArrayMethods.put(type, type.getMethod("toByteArray"));
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /**
     * @return The serialized proto of an item that is a proto, or wraps one such as the grid view
     *         items, or null if the item holds no proto, such as a header.
     */
    @Nullable
    private static byte[] serialize(@Nullable Object item) {
        if (item == null) return null;
        Class<?> itemClass = item.getClass();
        try {
            Object message = item;
            if (!isMessage(itemClass)) {
                Field field = wrappedMessageFields.get(itemClass);
                if (field == null) {
                    if (classesWithoutMessage.contains(itemClass)) return null;
                    for (Field candidate : itemClass.getDeclaredFields()) {
                        if (Modifier.isStatic(candidate.getModifiers())
                                || candidate.getType().isPrimitive()
                                || !isMessage(candidate.getType())) continue;
                        candidate.setAccessible(true);
                        field = candidate;
                        break;
                    }
                    if (field == null) {
                        classesWithoutMessage.add(itemClass);
                        return null;
                    }
                    wrappedMessageFields.put(itemClass, field);
                }
                message = field.get(item);
                if (message == null) return null;
            }
            Class<?> messageClass = message.getClass();
            return isMessage(messageClass) ? (byte[]) toByteArrayMethods.get(messageClass).invoke(message) : null;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not serialize: " + itemClass.getName(), ex);
            return null;
        }
    }

    /**
     * Adds a weak reference to a list, and removes the references to collected objects.
     */
    private static <T> void addWeakReference(List<WeakReference<T>> list, T object) {
        synchronized (list) {
            for (Iterator<WeakReference<T>> refs = list.iterator(); refs.hasNext(); ) {
                if (refs.next().get() == null) refs.remove();
            }
            list.add(new WeakReference<>(object));
        }
    }

    // region Library

    /**
     * Injection point.
     * Called when a presenter of the Library list or grid view is created.
     */
    public static void registerLibraryPresenter(Object presenter) {
        if (!(presenter instanceof LibraryPresenter) && !(presenter instanceof LibraryGridPresenter)) {
            Logger.printException(() -> "Not a Library presenter: " + presenter);
            return;
        }
        addWeakReference(libraryPresenters, presenter);
    }

    private static GridItems getGridItems(Object presenter) {
        return gridItems.computeIfAbsent(presenter, key -> new GridItems());
    }

    /**
     * Injection point.
     * Called when a grid presenter clears its rows, before it builds them again.
     */
    public static void onGridItemsReset(Object presenter) {
        synchronized (gridItems) {
            getGridItems(presenter).resetPending = true;
        }
    }

    /**
     * Injection point.
     * Called when a grid presenter lays out items in rows. Items it builds after this are added to the rows.
     */
    public static void onGridItemsBound(Object presenter) {
        synchronized (gridItems) {
            getGridItems(presenter).resetPending = false;
        }
    }

    /**
     * Injection point.
     * Called when a grid presenter starts to build the items of a renderer.
     */
    public static void onGridItemsBuilding(Object presenter) {
        buildingGridPresenter = new WeakReference<>(presenter);
    }

    /**
     * Injection point.
     *
     * @param items Items a grid presenter built from a renderer.
     * @return The items without the playlists hidden by the tag filter.
     */
    public static List<Object> filterGridItems(List<Object> items) {
        Object presenter = buildingGridPresenter.get();
        if (presenter != null && items != null) {
            synchronized (gridItems) {
                GridItems kept = getGridItems(presenter);
                if (kept.resetPending) {
                    kept.items.clear();
                    kept.resetPending = false;
                }
                kept.items.addAll(items);
            }
        }
        return filterLibraryItems(items);
    }

    /**
     * Injection point.
     *
     * @param items Library grid items, such as playlists, artists and podcasts.
     * @return The items without the playlists hidden by the tag filter. While only one tag
     *         is shown, items that are not playlists, such as mixes and artists, are removed too.
     */
    public static List<Object> filterLibraryItems(List<Object> items) {
        if (!isEnabled() || items == null) return items;

        try {
            List<Object> filtered = new ArrayList<>(items.size());
            boolean showOnlyTag = !PlaylistTagsStore.getOnlyTag().isEmpty();
            for (Object item : items) {
                byte[] bytes = serialize(item);
                PlaylistTagsProto.GridItem playlist = bytes == null ? null : PlaylistTagsProto.readGridItem(bytes);

                if (playlist != null) {
                    PlaylistTagsStore.rememberPlaylist(playlist.playlistId(), playlist.title());
                    if (PlaylistTagsStore.isPlaylistHidden(playlist.playlistId())) {
                        Logger.printDebug(() -> "Hiding playlist: " + playlist.title()
                                + " (" + playlist.playlistId() + ")");
                        continue;
                    }
                } else if (showOnlyTag && bytes != null) {
                    // Mixes, albums, artists and profiles can't be tagged. Items that are not
                    // a proto, such as headers, are kept.
                    continue;
                }
                filtered.add(item);
            }
            return filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterLibraryItems failure", ex);
            return items;
        }
    }

    /**
     * Injection point.
     * Called when the app removes a playlist from its lists, because the playlist was deleted
     * or removed from the Library. Its tags are forgotten.
     *
     * @param playlistId The id of the playlist.
     */
    public static void onPlaylistRemoved(@Nullable String playlistId) {
        if (playlistId == null) return;
        try {
            // Browse ids of playlists start with VL.
            String id = playlistId.startsWith("VL") ? playlistId.substring(2) : playlistId;
            if (PlaylistTagsStore.forgetPlaylist(id)) {
                Logger.printDebug(() -> "Forgot the tags of removed playlist: " + id);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPlaylistRemoved failure", ex);
        }
    }

    /**
     * Rebinds the Library list and grid views, so the current tag filter is applied.
     */
    static void reloadLibrary() {
        Utils.runOnMainThread(() -> {
            List<Object> presenters = new ArrayList<>();
            synchronized (libraryPresenters) {
                for (WeakReference<Object> ref : libraryPresenters) {
                    Object presenter = ref.get();
                    if (presenter != null) presenters.add(presenter);
                }
            }
            Logger.printDebug(() -> "Reloading " + presenters.size() + " Library presenters");
            for (Object presenter : presenters) {
                try {
                    if (presenter instanceof LibraryGridPresenter grid) {
                        List<Object> items;
                        synchronized (gridItems) {
                            GridItems kept = gridItems.get(presenter);
                            // A presenter that built no items yet has nothing to filter.
                            if (kept == null) continue;
                            items = new ArrayList<>(kept.items);
                        }
                        grid.patch_rebindItems(filterLibraryItems(items));
                    } else if (presenter instanceof LibraryPresenter list) {
                        list.patch_reloadLibrary();
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not reload the Library", ex);
                }
            }
            updateFilterButtons();
        });
    }

    // endregion

    // region Flyout menu

    /**
     * Injection point.
     * Called when the flyout menu bottom sheet starts.
     *
     * @param dialog The bottom sheet dialog.
     * @param menu   The menu renderer proto the bottom sheet shows.
     */
    public static void onMenuBottomSheetStart(@Nullable Dialog dialog, @Nullable Object menu) {
        if (!isEnabled() || dialog == null || menu == null) return;

        try {
            byte[] bytes = serialize(menu);
            if (bytes == null) return;
            String playlistId = PlaylistTagsProto.findMenuPlaylistId(bytes,
                    PlaylistTagsStore.getKnownPlaylists().keySet());
            if (playlistId == null) return;

            View list = dialog.findViewById(ResourceUtils.getIdIdentifier("bottom_sheet_list"));
            if (list == null || !(list.getParent() instanceof ViewGroup container)) {
                Logger.printDebug(() -> "Menu bottom sheet list not found");
                return;
            }

            Context context = container.getContext();
            View existing = container.findViewWithTag(MENU_ROW_TAG);
            if (existing != null) container.removeView(existing);

            LinearLayout row = new LinearLayout(context);
            row.setTag(MENU_ROW_TAG);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Dim.dp(56));
            row.setPadding(Dim.dp(24), Dim.dp8, Dim.dp(24), Dim.dp8);
            // The sheet background is drawn by the list, so the row above it needs its own.
            Drawable listBackground = list.getBackground();
            Drawable.ConstantState listBackgroundState = listBackground == null
                    ? null
                    : listBackground.getConstantState();
            if (listBackgroundState != null) {
                row.setBackground(listBackgroundState.newDrawable().mutate());
            } else {
                row.setBackgroundColor(ThemeUtils.getAppBackgroundColor());
            }
            row.setForeground(context.getDrawable(
                    ThemeUtils.getThemeResourceId(context, android.R.attr.selectableItemBackground)));

            row.addView(PlaylistTagsDialogs.createLineText(context,
                    str("morphe_music_playlist_tags_menu_item"), 16));
            row.addView(PlaylistTagsDialogs.createSummaryText(context,
                    PlaylistTagsDialogs.describeTags(PlaylistTagsStore.getPlaylistTags(playlistId)), 13));

            row.setOnClickListener(v -> {
                Context dialogContext = getDialogContext(context);
                dialog.dismiss();
                PlaylistTagsDialogs.showAssignDialog(dialogContext, playlistId, null);
            });

            container.addView(row, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "onMenuBottomSheetStart failure", ex);
        }
    }

    // endregion

    // region Library filter button

    /**
     * Injection point.
     * Called when the sort button of a list, such as the Library, is created.
     *
     * @param sortButton Root view of the sort button.
     */
    public static void onSortFilterButtonCreated(@Nullable View sortButton) {
        if (!isEnabled() || !(sortButton instanceof ViewGroup group)) return;

        try {
            if (group.findViewWithTag(FILTER_BUTTON_TAG) != null) return;
            Context context = group.getContext();

            TextView button = new TextView(context);
            button.setTag(FILTER_BUTTON_TAG);
            button.setGravity(Gravity.CENTER);
            button.setTextColor(ThemeUtils.getAppForegroundColor());
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setSingleLine(true);
            button.setMinHeight(Dim.dp(32));
            button.setPadding(Dim.dp12, Dim.dp4, Dim.dp12, Dim.dp4);
            button.setBackground(CustomDialog.createRoundedBackground(16,
                    Utils.adjustColorBrightness(ThemeUtils.getAppBackgroundColor(), 0.9f, 1.4f)));
            button.setOnClickListener(v -> PlaylistTagsDialogs.showFilterDialog(
                    getDialogContext(context), PlaylistTagsPatch::reloadLibrary));
            // The button may be reused after the filter changed on another screen.
            button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    // The same sort button class is used for the dropdown title of the toolbar.
                    view.setVisibility(isInsideList(view) ? View.VISIBLE : View.GONE);
                    updateFilterButtonText((TextView) view);
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                }
            });
            updateFilterButtonText(button);

            // The root is a FrameLayout that wraps its text, so place the button after the
            // dropdown chevron in the horizontal container instead of on top of the text.
            ViewGroup container = group;
            if (group.getChildCount() > 0 && group.getChildAt(0) instanceof LinearLayout linearLayout) {
                container = linearLayout;
            }
            ViewGroup.MarginLayoutParams params;
            if (container instanceof LinearLayout) {
                LinearLayout.LayoutParams linearParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                linearParams.gravity = Gravity.CENTER_VERTICAL;
                params = linearParams;
            } else {
                params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
            }
            params.setMarginStart(Dim.dp(12));
            container.addView(button, params);

            addWeakReference(filterButtons, button);
        } catch (Exception ex) {
            Logger.printException(() -> "onSortFilterButtonCreated failure", ex);
        }
    }

    private static boolean isInsideList(View view) {
        for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent.getClass().getName().endsWith("RecyclerView")) return true;
        }
        return false;
    }

    private static void updateFilterButtons() {
        synchronized (filterButtons) {
            for (WeakReference<TextView> ref : filterButtons) {
                TextView button = ref.get();
                if (button != null) updateFilterButtonText(button);
            }
        }
    }

    private static void updateFilterButtonText(TextView button) {
        String onlyTag = PlaylistTagsStore.getOnlyTag();
        int hiddenCount = PlaylistTagsStore.getHiddenTagCount();
        if (!onlyTag.isEmpty()) {
            button.setText(str("morphe_music_playlist_tags_filter_button_only", onlyTag));
        } else if (hiddenCount > 0) {
            button.setText(str("morphe_music_playlist_tags_filter_button_hidden", hiddenCount));
        } else {
            button.setText(str("morphe_music_playlist_tags_filter_button"));
        }
    }

    // endregion

    /**
     * @return The current activity if available, because the views of a dialog may use a
     *         themed context that can't host another dialog after the first one is dismissed.
     */
    private static Context getDialogContext(Context fallback) {
        Activity activity = Utils.getActivity();
        return activity != null ? activity : fallback;
    }
}
