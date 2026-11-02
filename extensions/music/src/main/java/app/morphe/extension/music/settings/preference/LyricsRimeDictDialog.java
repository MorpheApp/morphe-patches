/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
import app.morphe.extension.music.patches.lyrics.romanizers.RomanizationDict;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * The word lists the Rime Wordlist provider reads, one per language, each fetched from an address the
 * user pasted. Only Cantonese ships; every other list is theirs to add and to judge, so no
 * catalogue of dialects is bundled here.
 *
 * <p>A list is fetched only while its box is ticked, and Save is what acts on the boxes and starts
 * the download. Unticking a user's list removes its row and its files; the built-in list is only
 * switched off, because its row is the example an added list is meant to look like.
 *
 * <p>The lists are stored one line each, as {@code subtag|label|url|charSource}, and removed by
 * rewriting that setting. A built-in list has no line, so its switch is stored separately.
 */
public final class LyricsRimeDictDialog {

    private LyricsRimeDictDialog() {
    }

    /**
     * Shows the lists, each with a box that says whether it is read, and the buttons that save
     * the boxes and add another list.
     *
     * @param onChanged called after a Save changed what is read, so a caller showing a summary of
     *                  them can read it again.
     * @param onClosed  called once the dialog is gone, whichever way it was closed.
     */
    public static void show(Context context, @Nullable Runnable onChanged,
            @Nullable Runnable onClosed) {
        try {
            showList(context, onChanged, onClosed);
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the Rime list dialog", ex);
            if (onClosed != null) {
                onClosed.run();
            }
        }
    }

    /** The row boxes, so Save can read what the user left ticked. */
    private static final class Rows {
        final Map<String, CheckBox> boxes = new LinkedHashMap<>();

        Rows() {
        }
    }

    private static void showList(Context context, @Nullable Runnable onChanged,
            @Nullable Runnable onClosed) {
        final List<String> builtinCodes = RimeDictStore.builtinLanguages();
        final List<String> customCodes = RimeDictStore.customLanguages();

        final int fg = ThemeUtils.getAppForegroundColor();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        final Rows rows = new Rows();

        final Dialog[] listDialog = new Dialog[1];
        // The add form replaces this list; that handover is not the end of the session, so the
        // list's own close callback is not run for it.
        final boolean[] handover = {false};

        final TextView guidance = new TextView(context);
        guidance.setTextSize(14);
        guidance.setTextColor(withAlpha(fg, 0x99));
        guidance.setText(str("morphe_music_lyrics_rime_dict_empty"));
        guidance.setPadding(0, 0, 0, Dim.dp12);
        content.addView(guidance);
        // The download reports into this same hint, like the Local dictionary dialog does, so
        // the progress takes the place of the summary rather than adding a block below.
        final LyricsLocalRomanizationDownloads.ProgressSink sink = guidance::setText;

        final Pair<Dialog, LinearLayout> pair = CustomDialog.create(context,
                str("morphe_music_lyrics_rime_dict_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> { },
                () -> { },
                str("morphe_music_lyrics_rime_dict_add"),
                // Closed first, so the list does not stay behind the form it opened.
                () -> {
                    handover[0] = true;
                    listDialog[0].dismiss();
                    showAddSafely(context, onChanged, onClosed);
                },
                false);
        listDialog[0] = pair.first;

        // The shipped lists come first, unticked: a table is a download, not an arrival.
        for (String code : builtinCodes) {
            final CheckBox box = checkBox(context, fg,
                    label(RimeDictStore.builtinLabel(code), code),
                    RimeDictStore.isBuiltinRowSelected(code));
            content.addView(box);
            addNote(context, fg, content, RimeDictStore.summaryFor(code));
            rows.boxes.put(code, box);
        }
        // A stored row was asked for, so it opens ticked; unticking it is what removes it.
        for (String code : customCodes) {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            final CheckBox box = checkBox(context, fg,
                    label(RimeDictStore.customLabel(code), code), true);
            row.addView(box);
            if (RimeDictStore.isReserved(code)) {
                addNote(context, fg, row,
                        str("morphe_music_lyrics_rime_dict_reserved_row"));
            } else {
                addNote(context, fg, row, RimeDictStore.summaryFor(code));
            }
            content.addView(row);
            rows.boxes.put(code, box);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = Dim.dp12;
        pair.second.addView(scroll, Math.max(0, pair.second.getChildCount() - 1), scrollParams);

        // Save writes the choice and closes; the fetch runs in the background and answers with a
        // toast, the same model the Local dictionary dialog uses.
        final Button ok = button(pair.second, str("morphe_settings_save"));
        if (ok != null) {
            ok.setOnClickListener(v ->
                    save(rows, builtinCodes, customCodes, pair.first, sink, onChanged));
        }

        pair.first.setOnDismissListener(d -> {
            LyricsLocalRomanizationDownloads.detach(sink);
            if (!handover[0] && onClosed != null) {
                onClosed.run();
            }
        });
        LyricsLocalRomanizationDownloads.attach(sink);
        pair.first.show();
    }

    /** A ticked row, in the app's own colors rather than the dialog theme's. */
    private static CheckBox checkBox(Context context, int fg, CharSequence text, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(text);
        box.setChecked(checked);
        box.setTextColor(fg);
        final int[][] states = {{android.R.attr.state_checked}, {}};
        box.setButtonTintList(new ColorStateList(states, new int[]{fg, withAlpha(fg, 0x66)}));
        return box;
    }

    /** A name, with the code beside it when the two differ, so two lists stay distinguishable. */
    private static CharSequence label(@Nullable String name, String code) {
        return name == null ? code : code + "|" + name;
    }

    /** A second line under a row, smaller and dimmed, for the notes about what a row is. */
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private static void addNote(Context context, int fg, LinearLayout parent,
                                @Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            return;
        }
        TextView note = new TextView(context);
        note.setText(text);
        note.setTextSize(12);
        note.setTextColor(withAlpha(fg, 0x99));
        note.setPadding(Dim.dp12, 0, 0, Dim.dp8);
        parent.addView(note);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /**
     * Applies the ticks. A ticked list is kept and fetched, an unticked one is taken away with its
     * files, and the built-in list is only switched off because its row stays.
     *
     * <p>The files go while the setting still names them: a list's file names come from its own
     * row, so a removed row could no longer point at what it left on disk.
     */
    private static void save(Rows rows, List<String> builtinCodes, List<String> customCodes,
            Dialog dialog, LyricsLocalRomanizationDownloads.ProgressSink sink,
            @Nullable Runnable onChanged) {
        final Map<String, Boolean> builtinChoice = new LinkedHashMap<>();
        final List<String> removed = new ArrayList<>();
        final List<String> lostReading = new ArrayList<>();

        for (String code : builtinCodes) {
            final boolean on = isChecked(rows, code);
            builtinChoice.put(code, on);
            if (!on && RimeDictStore.isSelected(code)) {
                removed.add(code);
                lostReading.add(code);
            }
        }
        final List<String> keptCustom = new ArrayList<>();
        for (String code : customCodes) {
            if (isChecked(rows, code)) {
                keptCustom.add(code);
            } else {
                removed.add(code);
                // A reserved row never supplied a reading, so taking it away must not move the
                // language the user picked.
                if (RimeDictStore.isSelected(code)) lostReading.add(code);
            }
        }

        Utils.runOnBackgroundThread(() -> {
            for (String code : removed) {
                RimeDictStore.deleteTable(code);
            }
            Utils.runOnMainThread(() -> apply(builtinChoice, keptCustom, lostReading,
                    dialog, sink, onChanged));
        });
    }

    private static boolean isChecked(Rows rows, String code) {
        final CheckBox box = rows.boxes.get(code);
        return box != null && box.isChecked();
    }

    /** Writes the choice, then fetches whatever the ticks now ask for. */
    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private static void apply(Map<String, Boolean> builtinChoice, List<String> keptCustom,
                              List<String> lostReading, Dialog dialog,
                              LyricsLocalRomanizationDownloads.ProgressSink sink,
                              @Nullable Runnable onChanged) {
        for (Map.Entry<String, Boolean> entry : builtinChoice.entrySet()) {
            RimeDictStore.setBuiltinSelected(entry.getKey(), entry.getValue());
        }

        // Rewritten from the rows that stay, so a removed row leaves no line behind.
        final StringBuilder kept = new StringBuilder();
        for (String code : keptCustom) {
            final String line = RimeDictStore.customRow(code);
            if (line == null) continue;
            if (kept.length() > 0) kept.append('\n');
            kept.append(line);
        }
        Settings.LYRICS_RIME_CUSTOM_DICTS.save(kept.toString());
        RimeDictStore.reset();

        // A language whose only table was just taken away falls back to Automatic, so the
        // romanizer is not left pointed at nothing.
        final String chosen = currentSubtag();
        if (!chosen.isEmpty() && lostReading.contains(chosen)) {
            Settings.LYRICS_ROMANIZE_LANGUAGE.save("DEFAULT");
        }

        if (onChanged != null) {
            onChanged.run();
        }

        dialog.dismiss();

        if (!builtinChoice.containsValue(Boolean.TRUE) && keptCustom.isEmpty()) {
            // Nothing ticked is a valid way to stop reading, not a mistake to undo.
            return;
        }

        // A toast answers when the download ends, the same model the Local dictionary uses.
        RimeDictStore.ensureSelected(new RomanizationDict.Listener() {
            @Override
            public void onDownload(String fileName, int percent) {
                Utils.runOnMainThread(() -> {
                    if (sink != null) {
                        sink.onHint(str("morphe_music_lyrics_local_romanization_dict_downloading",
                                fileName, percent));
                    }
                });
            }

            @Override
            public void onBuilding(int percent) {
                Utils.runOnMainThread(() -> {
                    if (sink != null) {
                        sink.onHint(str("morphe_music_lyrics_rime_dict_building", percent));
                    }
                });
            }

            @Override
            public void onReady(int entries) {
                Utils.runOnMainThread(() -> {
                    if (sink != null) {
                        sink.onHint(str("morphe_music_lyrics_rime_dict_ready"));
                    }
                    Utils.showToastShort(str("morphe_music_lyrics_rime_dict_ready_toast",
                            entries));
                });
            }

            @Override
            public void onFailed() {
                Utils.runOnMainThread(() -> {
                    if (sink != null) {
                        sink.onHint(str("morphe_music_lyrics_rime_dict_failed"));
                    }
                    Utils.showToastShort(str("morphe_music_lyrics_rime_dict_failed"));
                });
            }
        });
    }

    /** Found by label because the button row is reordered when the labels do not fit. */
    @Nullable
    private static Button button(ViewGroup layout, CharSequence text) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            final View child = layout.getChildAt(i);
            if (child instanceof Button candidate
                    && text.toString().contentEquals(candidate.getText())) {
                return candidate;
            }
            if (child instanceof ViewGroup group) {
                final Button found = button(group, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Starts the form, tolerating a settings screen that has already gone away. */
    private static void showAddSafely(Context context, @Nullable Runnable onChanged,
            @Nullable Runnable onClosed) {
        try {
            showAdd(context, "", "", "", charSourceOptions().first[0], onChanged, onClosed);
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the Rime add dialog", ex);
        }
    }

    /** Reopens the form with what was typed, after a refusal named the field at fault. */
    private static void showAddSafely(Context context, EditText code, EditText label,
            EditText url, String charSource, @Nullable Runnable onChanged,
            @Nullable Runnable onClosed) {
        final String codeText = code.getText().toString();
        final String labelText = label.getText().toString();
        final String urlText = url.getText().toString();
        try {
            showAdd(context, codeText, labelText, urlText, charSource, onChanged, onClosed);
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the Rime add dialog", ex);
        }
    }

    /** Asks for the four things a list is: what language it reads, what to call it, where it is,
     * and which character table to fall back to. */
    private static void showAdd(Context context, String codeText, String labelText,
            String urlText, String charSourceToken, @Nullable Runnable onChanged,
            @Nullable Runnable onClosed) {
        final int fg = ThemeUtils.getAppForegroundColor();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        // The hint sits inside the form's own scroll area, in the dimmed note style the list
        // dialog uses, rather than as the dialog message that would add a second scroll area.
        final TextView hint = new TextView(context);
        hint.setTextSize(14);
        hint.setTextColor(withAlpha(fg, 0x99));
        hint.setText(str("morphe_music_lyrics_rime_dict_add_hint"));
        hint.setPadding(0, 0, 0, Dim.dp12);
        content.addView(hint);

        EditText codeInput = addField(context, content,
                "morphe_music_lyrics_rime_dict_field_code",
                "morphe_music_lyrics_rime_dict_field_code_hint");
        codeInput.setText(codeText);
        EditText labelInput = addField(context, content,
                "morphe_music_lyrics_rime_dict_field_label",
                "morphe_music_lyrics_rime_dict_field_label_hint");
        labelInput.setText(labelText);
        EditText urlInput = addField(context, content,
                "morphe_music_lyrics_rime_dict_field_url",
                "morphe_music_lyrics_rime_dict_field_url_hint");
        urlInput.setText(urlText);
        urlInput.setSingleLine(true);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);

        final String[] charSource = {charSourceToken};
        TextView charSourceValue = new TextView(context);
        charSourceValue.setText(charSourceLabel(charSource[0]));
        charSourceValue.setTextColor(ThemeUtils.getAppForegroundColor());
        charSourceValue.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
        charSourceValue.setOnClickListener(v -> {
            charSource[0] = nextCharSource(charSource[0]);
            charSourceValue.setText(charSourceLabel(charSource[0]));
        });
        addLabel(context, content, "morphe_music_lyrics_rime_dict_field_char_source");
        content.addView(charSourceValue);

        // The accent button saves and closes; a refusal toasts the faulty field and reopens the
        // form with what was typed, while Cancel and back do not reopen.
        final int[] outcome = {0}; // 0 closed by the user, 1 saved, 2 refused
        // The OK action runs after create() returns, so it reaches the dialog through a holder.
        final Dialog[] dialogBox = new Dialog[1];
        final Pair<Dialog, LinearLayout> pair = CustomDialog.create(context,
                str("morphe_music_lyrics_rime_dict_add_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    final boolean ok = add(codeInput, labelInput, urlInput, charSource[0]);
                    outcome[0] = ok ? 1 : 2;
                    if (ok) dialogBox[0].dismiss();
                },
                () -> {},
                null,
                null,
                true);
        dialogBox[0] = pair.first;

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = Dim.dp12;
        pair.second.addView(scroll, Math.max(0, pair.second.getChildCount() - 1), scrollParams);

        pair.first.setOnDismissListener(d -> {
            if (outcome[0] == 1) {
                if (onChanged != null) {
                    onChanged.run();
                }
                showList(context, onChanged, onClosed);
            } else if (outcome[0] == 2 && filled(codeInput, labelInput, urlInput)) {
                showAddSafely(context, codeInput, labelInput, urlInput, charSource[0],
                        onChanged, onClosed);
            } else if (onClosed != null) {
                onClosed.run();
            }
        });

        pair.first.show();
    }

    /** Whether anything was typed, so an empty form that was refused is not reopened. */
    private static boolean filled(EditText code, EditText label, EditText url) {
        return !code.getText().toString().trim().isEmpty()
                || !label.getText().toString().trim().isEmpty()
                || !url.getText().toString().trim().isEmpty();
    }

    private static EditText addField(Context context, LinearLayout content, String labelKey,
            String hintKey) {
        addLabel(context, content, labelKey);
        EditText input = ThemedInput.create(context);
        input.setHint(str(hintKey));
        content.addView(input);
        return input;
    }

    private static void addLabel(Context context, LinearLayout content, String key) {
        TextView label = new TextView(context);
        label.setText(str(key));
        label.setTextSize(14);
        label.setTextColor(ThemeUtils.getAppForegroundColor());
        label.setPadding(0, Dim.dp12, 0, Dim.dp8);
        content.addView(label);
    }

    /**
     * Stores one list. Everything is checked here rather than at download time, so a typo is
     * reported while the user is looking at the field that holds it.
     *
     * @return whether the list was added.
     */
    @SuppressWarnings("ConstantValue")
    private static boolean add(EditText codeInput, EditText labelInput, EditText urlInput,
                               String charSource) {
        final String code = codeInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        final String label = labelInput.getText().toString().trim();
        final String url = urlInput.getText().toString().trim();

        if (code.isEmpty() || label.isEmpty() || url.isEmpty()) {
            Utils.showToastShort(str("morphe_music_lyrics_rime_dict_incomplete"));
            return false;
        }
        if (!code.matches("[a-z]{2,3}")) {
            Utils.showToastShort(str("morphe_music_lyrics_rime_dict_bad_code"));
            return false;
        }
        if (!url.startsWith("https://") || url.length() == "https://".length()) {
            Utils.showToastShort(str("morphe_music_lyrics_rime_dict_bad_url"));
            return false;
        }
        // The line format is pipe separated, so a pipe or a line break in either field would
        // shift the rest of the line.
        if (label.indexOf('|') >= 0 || url.indexOf('|') >= 0
                || label.indexOf('\n') >= 0 || url.indexOf('\n') >= 0) {
            Utils.showToastShort(str("morphe_music_lyrics_rime_dict_bad_field"));
            return false;
        }
        if (RimeDictStore.hasLanguage(code)) {
            Utils.showToastShort(str("morphe_music_lyrics_rime_dict_duplicate", code));
            return false;
        }
        if (!RimeDictStore.canAddLanguage(code)) {
            // A language the build already reads keeps its own reading.
            Utils.showToastShort(str("morphe_music_lyrics_rime_dict_reserved", code));
            return false;
        }

        final String line = code + "|" + label + "|" + url + "|" + charSource;
        final String stored = Settings.LYRICS_RIME_CUSTOM_DICTS.get();
        final String joined = stored == null || stored.isBlank() ? line : stored + "\n" + line;
        Settings.LYRICS_RIME_CUSTOM_DICTS.save(joined);

        // The store parses the setting lazily, so the new row is seen on the next read.
        RimeDictStore.reset();
        Utils.showToastShort(str("morphe_music_lyrics_rime_dict_added", label));

        // Nothing is fetched here: the new row comes back ticked, and the Save that keeps it is
        // what starts the download.
        return true;
    }

    /** The BCP-47 primary subtag of the chosen romanization language, or "" for Automatic. */
    @SuppressWarnings("ConstantValue")
    private static String currentSubtag() {
        final String language = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        if (language == null || language.isBlank()
                || "DEFAULT".equalsIgnoreCase(language)) {
            return "";
        }
        final String lower = language.toLowerCase(Locale.ROOT);
        return lower.split("-", 2)[0];
    }

    /** The character source options, as saved tokens and the labels that name them. Both come
     * from arrays.xml in the same order, so another option is one row in each array and nothing
     * here. */
    private static Pair<String[], String[]> charSourceOptions() {
        try {
            final String[] tokens = ResourceUtils.getStringArray(
                    "morphe_music_lyrics_rime_char_source_values");
            final String[] labels = ResourceUtils.getStringArray(
                    "morphe_music_lyrics_rime_char_source_entries");
            if (tokens.length > 0 && tokens.length == labels.length) {
                return new Pair<>(tokens, labels);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the character source list", ex);
        }
        return new Pair<>(new String[]{"AUTO"}, new String[]{
                str("morphe_music_lyrics_rime_char_source_auto")});
    }

    private static String nextCharSource(String current) {
        final String[] tokens = charSourceOptions().first;
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equalsIgnoreCase(current)) {
                return tokens[(i + 1) % tokens.length];
            }
        }
        return tokens[0];
    }

    /** The label beside the token in the arrays, so a renamed option needs no code change. */
    private static CharSequence charSourceLabel(String token) {
        final Pair<String[], String[]> options = charSourceOptions();
        for (int i = 0; i < options.first.length; i++) {
            if (options.first[i].equalsIgnoreCase(token)) {
                return options.second[i];
            }
        }
        return options.second[0];
    }
}
