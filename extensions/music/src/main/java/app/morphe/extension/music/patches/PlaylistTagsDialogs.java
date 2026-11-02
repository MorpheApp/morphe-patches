/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * Dialogs to tag a playlist, to filter the Library by tag, and to manage tags.
 */
public final class PlaylistTagsDialogs {

    /**
     * Background of the app's own sheets, such as the one to save a song to a playlist.
     */
    @ColorInt
    private static final int SHEET_DARK_COLOR = 0xFF212121;

    private static final int SHEET_ANIMATION_DURATION = 200;

    private PlaylistTagsDialogs() {
    }

    // region Tag a playlist

    /**
     * Shows the tags of a playlist in a sheet like the one to save a song to a playlist:
     * a row to create a tag, then a row for each tag, checked if the playlist has it.
     * The tags are saved when the sheet is closed.
     *
     * @param onDone Called after the sheet was closed.
     */
    public static void showAssignDialog(Context context, String playlistId, @Nullable Runnable onDone) {
        try {
            Set<String> original = PlaylistTagsStore.getPlaylistTags(playlistId);
            Set<String> selected = new LinkedHashSet<>(original);

            // The app draws its sheets lighter than its background.
            SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog.createMainLayout(
                    context, Utils.isDarkModeEnabled() ? SHEET_DARK_COLOR : null);

            TextView title = createLineText(context, str("morphe_music_playlist_tags_assign_title",
                    PlaylistTagsStore.getPlaylistTitle(playlistId)), 20);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(Dim.dp16, Dim.dp16, Dim.dp16, Dim.dp16);
            mainLayout.addView(title);

            View divider = new View(context);
            divider.setBackgroundColor(withAlpha(ThemeUtils.getAppForegroundColor(), 0.1f));
            mainLayout.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp(1)));

            LinearLayout rows = new LinearLayout(context);
            rows.setOrientation(LinearLayout.VERTICAL);
            rows.setPadding(0, Dim.dp8, 0, Dim.dp8);
            ScrollView scroll = SheetBottomDialog.createCappedScrollView(context);
            scroll.addView(rows);
            mainLayout.addView(scroll);

            rows.addView(createSheetRow(context, createIcon(context, "yt_outline_add_vd_theme_24", "+"),
                    str("morphe_music_playlist_tags_new_tag"), null, null,
                    () -> showNewTagDialog(context, name -> {
                        selected.add(name);
                        rows.addView(createTagRow(context, name, original, selected));
                    })));
            for (String tag : PlaylistTagsStore.getTags()) {
                rows.addView(createTagRow(context, tag, original, selected));
            }

            SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                    context, mainLayout, SHEET_ANIMATION_DURATION);
            dialog.setOnDismissListener(d -> {
                if (!selected.equals(original)) {
                    PlaylistTagsStore.setPlaylistTags(playlistId, selected);
                    PlaylistTagsPatch.reloadLibrary();
                }
                if (onDone != null) onDone.run();
            });
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showAssignDialog failure", ex);
        }
    }

    /**
     * A row of a tag, with how many playlists have it, and a check mark if the playlist has it.
     * Tapping it adds or removes the tag.
     */
    private static View createTagRow(Context context, String tag, Set<String> original, Set<String> selected) {
        // Playlists other than this one that have the tag.
        int otherPlaylists = PlaylistTagsStore.getPlaylistCount(tag) - (original.contains(tag) ? 1 : 0);
        TextView summary = createSummaryText(context, "", 14);
        View check = createIcon(context, "yt_outline_check_vd_theme_24", "✓");
        check.setBackground(null);

        Runnable update = () -> {
            boolean checked = selected.contains(tag);
            check.setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
            int count = otherPlaylists + (checked ? 1 : 0);
            summary.setText(count == 1
                    ? str("morphe_music_playlist_tags_playlist_count_one")
                    : str("morphe_music_playlist_tags_playlist_count", count));
        };
        update.run();

        return createSheetRow(context, createIcon(context, null, "#"), tag, summary, check, () -> {
            if (!selected.remove(tag)) selected.add(tag);
            update.run();
        });
    }

    // endregion

    // region Filter the Library

    /**
     * Shows which tags are visible, and optionally a single tag to show only.
     *
     * @param onChanged Called if the filter changed.
     */
    public static void showFilterDialog(Context context, Runnable onChanged) {
        try {
            List<String> tags = PlaylistTagsStore.getTags();
            if (tags.isEmpty()) {
                Utils.showToastLong(str("morphe_music_playlist_tags_none"));
                return;
            }

            String originalOnlyTag = PlaylistTagsStore.getOnlyTag();
            String[] onlyTag = {originalOnlyTag};
            Map<String, Boolean> visibility = new LinkedHashMap<>();
            for (String tag : tags) {
                visibility.put(tag, !PlaylistTagsStore.isTagHidden(tag));
            }

            LinearLayout content = createContentLayout(context);

            // Show only one tag.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_filter_show_only")));
            RadioGroup radioGroup = new RadioGroup(context);
            radioGroup.addView(createRadioButton(context, str("morphe_music_playlist_tags_filter_all"),
                    originalOnlyTag.isEmpty(), () -> onlyTag[0] = ""));
            for (String tag : tags) {
                radioGroup.addView(createRadioButton(context, tag,
                        tag.equals(originalOnlyTag), () -> onlyTag[0] = tag));
            }
            content.addView(radioGroup);

            // Visible tags.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_filter_visible")));
            for (String tag : tags) {
                Switch tagSwitch = new Switch(context);
                tagSwitch.setText(tag);
                tagSwitch.setTextColor(ThemeUtils.getAppForegroundColor());
                tagSwitch.setMinHeight(Dim.dp(48));
                tagSwitch.setChecked(Boolean.TRUE.equals(visibility.get(tag)));
                tagSwitch.setOnCheckedChangeListener((button, checked) -> visibility.put(tag, checked));
                content.addView(tagSwitch);
            }

            Pair<Dialog, LinearLayout> dialog = createDialog(context,
                    str("morphe_music_playlist_tags_filter_title"),
                    content,
                    () -> {
                        boolean changed = PlaylistTagsStore.setOnlyTag(onlyTag[0]);
                        for (Map.Entry<String, Boolean> entry : visibility.entrySet()) {
                            changed |= PlaylistTagsStore.setTagHidden(entry.getKey(), !entry.getValue());
                        }
                        if (changed) onChanged.run();
                    },
                    () -> {},
                    str("morphe_music_playlist_tags_manage"),
                    () -> showManagerDialog(context));
            dialog.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showFilterDialog failure", ex);
        }
    }

    private static RadioButton createRadioButton(Context context, String text, boolean checked, Runnable onChecked) {
        RadioButton button = new RadioButton(context);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextColor(ThemeUtils.getAppForegroundColor());
        button.setButtonTintList(ColorStateList.valueOf(ThemeUtils.getAppForegroundColor()));
        button.setChecked(checked);
        button.setOnCheckedChangeListener((view, isChecked) -> {
            if (isChecked) onChecked.run();
        });
        return button;
    }

    // endregion

    // region Manage tags

    /**
     * Lists all tags, to rename or delete them, and the playlists seen in the Library, to tag them.
     */
    public static void showManagerDialog(Context context) {
        try {
            LinearLayout content = createContentLayout(context);
            final Dialog[] managerDialog = new Dialog[1];
            Runnable reopen = () -> {
                if (managerDialog[0] != null) managerDialog[0].dismiss();
                showManagerDialog(context);
            };

            // Tags.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_section_tags")));
            List<String> tags = PlaylistTagsStore.getTags();
            for (String tag : tags) {
                String summary = PlaylistTagsStore.isTagHidden(tag)
                        ? str("morphe_music_playlist_tags_hidden")
                        : null;
                content.addView(createListRow(context, tag, summary,
                        () -> showEditTagDialog(context, tag, reopen)));
            }
            content.addView(createListRow(context, str("morphe_music_playlist_tags_new_tag"), null,
                    () -> showNewTagDialog(context, name -> reopen.run())));

            // Playlists, tagged first.
            content.addView(createSectionHeader(context, str("morphe_music_playlist_tags_section_playlists")));
            Map<String, String> playlists = PlaylistTagsStore.getKnownPlaylists();
            if (playlists.isEmpty()) {
                TextView empty = createText(context, str("morphe_music_playlist_tags_no_playlists"), 14);
                empty.setAlpha(0.7f);
                content.addView(empty);
            } else {
                Map<String, Set<String>> playlistTags = new HashMap<>();
                for (String id : playlists.keySet()) {
                    playlistTags.put(id, PlaylistTagsStore.getPlaylistTags(id));
                }
                List<Map.Entry<String, String>> sorted = new ArrayList<>(playlists.entrySet());
                sorted.sort((a, b) -> {
                    boolean aTagged = !playlistTags.get(a.getKey()).isEmpty();
                    boolean bTagged = !playlistTags.get(b.getKey()).isEmpty();
                    if (aTagged != bTagged) return aTagged ? -1 : 1;
                    return a.getValue().compareToIgnoreCase(b.getValue());
                });
                for (Map.Entry<String, String> playlist : sorted) {
                    String summary = describeTags(playlistTags.get(playlist.getKey()));
                    content.addView(createListRow(context, playlist.getValue(), summary,
                            () -> {
                                managerDialog[0].dismiss();
                                showAssignDialog(context, playlist.getKey(), () -> showManagerDialog(context));
                            }));
                }
            }

            Pair<Dialog, LinearLayout> dialog = createDialog(context,
                    str("morphe_music_playlist_tags_manage_title"),
                    content, () -> {}, null, null, null);
            managerDialog[0] = dialog.first;
            dialog.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showManagerDialog failure", ex);
        }
    }

    /**
     * @param onCreated Called with the name of the new tag.
     */
    private static void showNewTagDialog(Context context, Consumer<String> onCreated) {
        EditText editText = CustomDialog.createEditText(context);
        editText.setSingleLine(true);
        editText.setHint(str("morphe_music_playlist_tags_new_tag_hint"));

        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(context,
                str("morphe_music_playlist_tags_new_tag"), null, editText, null,
                () -> {
                    String name = PlaylistTagsStore.normalizeTagName(editText.getText().toString());
                    if (name.isEmpty()) return;
                    if (!PlaylistTagsStore.addTag(name)) {
                        Utils.showToastShort(str("morphe_music_playlist_tags_tag_exists", name));
                        return;
                    }
                    onCreated.accept(name);
                },
                () -> {}, null, null, false);
        dialog.first.show();
    }

    private static void showEditTagDialog(Context context, String tag, Runnable onDone) {
        EditText editText = CustomDialog.createEditText(context);
        editText.setSingleLine(true);
        editText.setText(tag);

        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(context,
                str("morphe_music_playlist_tags_edit_tag_title"), null, editText,
                str("morphe_music_playlist_tags_rename"),
                () -> {
                    String name = PlaylistTagsStore.normalizeTagName(editText.getText().toString());
                    if (!PlaylistTagsStore.renameTag(tag, name)) {
                        Utils.showToastShort(str("morphe_music_playlist_tags_tag_exists", name));
                        return;
                    }
                    PlaylistTagsPatch.reloadLibrary();
                    onDone.run();
                },
                () -> {},
                str("morphe_music_playlist_tags_delete"),
                () -> {
                    PlaylistTagsStore.deleteTag(tag);
                    PlaylistTagsPatch.reloadLibrary();
                    onDone.run();
                },
                true);
        dialog.first.show();
    }

    // endregion

    // region Views

    /**
     * @return The tags of a playlist as text, or a text saying it has none.
     */
    static String describeTags(Set<String> tags) {
        return tags.isEmpty()
                ? str("morphe_music_playlist_tags_menu_item_none")
                : TextUtils.join(", ", tags);
    }

    @ColorInt
    private static int withAlpha(@ColorInt int color, float alpha) {
        return Color.argb(Math.round(255 * alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    /**
     * A square like the thumbnails of the sheet to save a song to a playlist, with an icon of the app,
     * or a text if the app has no such icon.
     *
     * @param drawableName Name of the icon, or null to show the text.
     */
    private static View createIcon(Context context, @Nullable String drawableName, String fallbackText) {
        int foregroundColor = ThemeUtils.getAppForegroundColor();
        FrameLayout box = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(withAlpha(foregroundColor, 0.1f));
        background.setCornerRadius(Dim.dp2);
        box.setBackground(background);

        int identifier = drawableName == null ? 0 : ResourceUtils.getDrawableIdentifier(drawableName);
        if (identifier != 0) {
            // From the activity context, because some icons are filled with a theme attribute.
            Drawable drawable = context.getDrawable(identifier).mutate();
            drawable.setTint(foregroundColor);
            ImageView image = new ImageView(context);
            image.setImageDrawable(drawable);
            box.addView(image, new FrameLayout.LayoutParams(Dim.dp24, Dim.dp24, Gravity.CENTER));
        } else {
            TextView text = createText(context, fallbackText, 22);
            box.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        }
        return box;
    }

    /**
     * A row of the sheet to tag a playlist: an icon, a title, an optional summary and an optional view at the end.
     *
     * @param summary A text of {@link #createSummaryText}, which the caller may update.
     */
    private static View createSheetRow(Context context, View icon, String title, @Nullable TextView summary,
                                       @Nullable View end, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp(64));
        row.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);
        row.setBackgroundResource(ThemeUtils.getThemeResourceId(context, android.R.attr.selectableItemBackground));
        row.setOnClickListener(v -> onClick.run());
        row.addView(icon, new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textsParams.setMarginStart(Dim.dp16);
        row.addView(texts, textsParams);

        texts.addView(createLineText(context, title, 16));
        if (summary != null) texts.addView(summary);

        if (end != null) {
            row.addView(end, new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48));
        }
        return row;
    }

    private static Pair<Dialog, LinearLayout> createDialog(Context context, String title, View content,
                                                           Runnable onOk, @Nullable Runnable onCancel,
                                                           @Nullable String neutralText,
                                                           @Nullable Runnable onNeutral) {
        Pair<Dialog, LinearLayout> dialog = CustomDialog.create(context, title, null, null, null,
                onOk, onCancel, neutralText, onNeutral, true);

        // Limit the height, so the buttons stay on screen with many tags or playlists.
        ScrollView scroll = SheetBottomDialog.createCappedScrollView(context, 55);
        scroll.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // Insert before the button row.
        dialog.second.addView(scroll, dialog.second.getChildCount() - 1, scrollParams);
        return dialog;
    }

    private static LinearLayout createContentLayout(Context context) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, Dim.dp4, 0, Dim.dp8);
        return content;
    }

    private static TextView createSectionHeader(Context context, String text) {
        TextView header = createText(context, text, 14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, Dim.dp12, 0, Dim.dp4);
        return header;
    }

    private static TextView createText(Context context, String text, int sizeSp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(ThemeUtils.getAppForegroundColor());
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    /**
     * A text cut to one line, such as the title of a row.
     */
    static TextView createLineText(Context context, String text, int sizeSp) {
        TextView view = createText(context, text, sizeSp);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /**
     * A dimmed text cut to one line, such as the summary of a row.
     */
    static TextView createSummaryText(Context context, String text, int sizeSp) {
        TextView view = createLineText(context, text, sizeSp);
        view.setAlpha(0.7f);
        return view;
    }

    private static View createListRow(Context context, String title, @Nullable String summary, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setMinimumHeight(Dim.dp(48));
        row.setPadding(0, Dim.dp8, 0, Dim.dp8);
        row.setOnClickListener(v -> onClick.run());

        row.addView(createLineText(context, title, 16));
        if (summary != null) row.addView(createSummaryText(context, summary, 13));
        return row;
    }

    // endregion
}
