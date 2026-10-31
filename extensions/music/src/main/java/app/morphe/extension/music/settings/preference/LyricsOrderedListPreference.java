/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.preference.Preference;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.derive.AiProviders;
import app.morphe.extension.music.patches.lyrics.romanizers.RimeDictStore;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Settings row that shows providers in priority order, with a switch for each one. Dragging
 * reorders them. The same row serves lyrics sources, translation and romanization providers.
 * The value is a comma separated id list; a leading {@code -} disables a provider.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class LyricsOrderedListPreference extends Preference {

    private static final String KEY_TRANSLATION_PROVIDER =
            "morphe_music_lyrics_translation_provider";
    private static final String KEY_ROMANIZATION_PROVIDER =
            "morphe_music_lyrics_romanization_provider";

    /** The fixed last row of the service list; it is not a lookup source and holds no order. */
    private static final String QUICKLRC = "QuickLRC";

    /** The grip column, the same width on every row so the names start at one line. */
    private static final int GRIP_COLUMN = Dim.dp24;

    private static final List<String> SOURCE_PROVIDER_ORDER = Arrays.asList(
            "Apple", "BiniLyrics", "LrcRed", "bLyrics", "Lyricify", "Unison",
            "PetitLyrics", "AMLL", "QQ", "SimpMusic", "KuGou", "Musixmatch",
            "YTMusic", "LRCLIB", "NetEase", "Luna", "LunaBeat",
            "Captions", "Spotify", "Deezer", "RMM", "Genius");

    /** Friendlier labels for display; ids not present here are shown verbatim. */
    private static final Map<String, String> PROVIDER_LABELS = new HashMap<>();
    static {
        PROVIDER_LABELS.put("YTMusic", "YouTube Music");
        PROVIDER_LABELS.put("Captions", "YouTube Captions (Auto) *");
        PROVIDER_LABELS.put("Apple", "Apple Music");
        PROVIDER_LABELS.put("RMM", "RMM Revival");
        PROVIDER_LABELS.put("Luna", "Soda (Luna)");
        PROVIDER_LABELS.put("bLyrics", "BetterLyrics (bLyrics)");
        PROVIDER_LABELS.put("BiniLyrics", "BiniLyrics (Binimum)");
        PROVIDER_LABELS.put("Musixmatch", "Musixmatch");
        PROVIDER_LABELS.put("Spotify", "Spotify *");
        PROVIDER_LABELS.put("Deezer", "Deezer *");
        PROVIDER_LABELS.put("Genius", "Genius");
        PROVIDER_LABELS.put("LrcRed", "lrc.red");
        PROVIDER_LABELS.put(QUICKLRC, "QuickLRC *");
    }

    private static final Map<String, String> PROVIDER_URLS = new HashMap<>();
    static {
        PROVIDER_URLS.put("LRCLIB", "https://lrclib.net/");
        PROVIDER_URLS.put("QQ", "https://y.qq.com/");
        PROVIDER_URLS.put("NetEase", "https://music.163.com/");
        PROVIDER_URLS.put("KuGou", "https://www.kugou.com/");
        PROVIDER_URLS.put("Luna", "https://www.qishui.com/");
        PROVIDER_URLS.put("PetitLyrics", "https://petitlyrics.com/");
        PROVIDER_URLS.put("bLyrics", "https://betterlyrics.org/");
        PROVIDER_URLS.put("BiniLyrics", "https://lyrics.binimum.org/");
        PROVIDER_URLS.put("Unison", "https://unison.boidu.dev/");
        PROVIDER_URLS.put("SimpMusic", "https://lyrics.simpmusic.org/");
        PROVIDER_URLS.put("AMLL", "https://amlldb.bikonoo.com/");
        PROVIDER_URLS.put("LunaBeat", "https://2755337087.github.io/ttml-hub/");
        PROVIDER_URLS.put("Lyricify", "https://lyricify.app/");
        PROVIDER_URLS.put("RMM", "https://rmmreviv.al/");
        PROVIDER_URLS.put("Genius", "https://genius.com/");
        PROVIDER_URLS.put("LrcRed", "https://lrc.red/");
    }

    private static String sourceProviderLabel(String id) {
        String label = PROVIDER_LABELS.get(id);
        return label != null ? label : id;
    }

    private enum Kind {
        SOURCE, TRANSLATION, ROMANIZATION
    }

    private static final class Item {
        final String id;
        boolean enabled;

        Item(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /**
     * A CheckBox defaults to the dialog theme's own label colour, which the dark app theme leaves
     * black on a dark dialog. Its box follows the text colour too, so both are set from the app.
     */
    private static void styleCheckBox(CheckBox box, int fg) {
        box.setTextColor(fg);
        final int[][] states = {{android.R.attr.state_checked}, {}};
        box.setButtonTintList(new ColorStateList(states, new int[]{fg, withAlpha(fg, 0x66)}));
    }

    private Kind kind() {
        final String key = getKey();
        if (KEY_TRANSLATION_PROVIDER.equals(key)) {
            return Kind.TRANSLATION;
        }
        if (KEY_ROMANIZATION_PROVIDER.equals(key)) {
            return Kind.ROMANIZATION;
        }
        return Kind.SOURCE;
    }

    private List<String> providerOrder() {
        return switch (kind()) {
            case SOURCE -> SOURCE_PROVIDER_ORDER;
            case TRANSLATION -> AiProviders.TRANSLATION_ORDER;
            case ROMANIZATION -> AiProviders.ROMANIZATION_ORDER;
        };
    }

    private StringSetting providerSetting() {
        return switch (kind()) {
            case SOURCE -> Settings.LYRICS_SOURCE;
            case TRANSLATION -> Settings.LYRICS_TRANSLATION_PROVIDERS;
            case ROMANIZATION -> Settings.LYRICS_ROMANIZATION_PROVIDERS;
        };
    }

    private String providerLabel(String id) {
        final String label = switch (kind()) {
            case SOURCE -> sourceProviderLabel(id);
            case TRANSLATION -> switch (id) {
                case AiProviders.EMBEDDED -> AiProviders.EMBEDDED_LABEL;
                case AiProviders.GOOGLE -> AiProviders.GOOGLE_LABEL;
                case AiProviders.OPENAI_COMPATIBLE -> AiProviders.OPENAI_COMPATIBLE_LABEL;
                case AiProviders.DEEPL -> AiProviders.DEEPL_LABEL;
                default -> id;
            };
            case ROMANIZATION -> switch (id) {
                case AiProviders.EMBEDDED -> AiProviders.EMBEDDED_LABEL;
                case AiProviders.GOOGLE -> AiProviders.GOOGLE_LABEL;
                case AiProviders.OPENAI_COMPATIBLE -> AiProviders.OPENAI_COMPATIBLE_LABEL;
                default -> str("morphe_music_lyrics_romanization_provider_" + id);
            };
        };
        // The star tells the reader that the name opens a dialog, the same as the source list.
        return opensSettingsDialog(id) ? label + " *" : label;
    }

    private boolean isLyricsSource() {
        return kind() == Kind.SOURCE;
    }

    private static boolean isLocalProvider(String id) {
        return AiProviders.LOCAL.equals(id);
    }

    private boolean isRimeProvider(String id) {
        return kind() == Kind.ROMANIZATION && AiProviders.RIME.equals(id);
    }

    /** The star marks a row that needs a token before it can be on, as in the source list. */
    private static boolean isQuickLrcKeyMissing() {
        final String key = Settings.LYRICS_TRANSCRIBE_KEY.get();
        return key == null || key.trim().isEmpty();
    }

    /** Providers whose settings are entered in a dialog when their name is tapped. */
    private boolean hasConfigDialog(String id) {
        if (isLyricsSource()) {
            return false;
        }
        return switch (id) {
            case AiProviders.OPENAI_COMPATIBLE -> true;
            case AiProviders.DEEPL -> kind() == Kind.TRANSLATION;
            default -> false;
        };
    }

    /**
     * Whether the name of a row opens a dialog. The star in the row is what tells the reader that,
     * so it covers the offline rows too, which open their own dictionary chooser rather than
     * being validated against a server.
     */
    private boolean opensSettingsDialog(String id) {
        return hasConfigDialog(id) || isLocalProvider(id) || isRimeProvider(id);
    }

    private void showConfigDialog(String id) {
        if (!(getContext() instanceof android.app.Activity activity)
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        LyricsProviderConfigDialog.show(activity, id, promptSettingFor(kind()), this::updateSummary);
    }

    /** The prompt an AI service is sent; DeepL translates without one. */
    @Nullable
    private static StringSetting promptSettingFor(Kind kind) {
        return switch (kind) {
            case TRANSLATION -> Settings.LYRICS_AI_TRANSLATION_PROMPT;
            case ROMANIZATION -> Settings.LYRICS_AI_ROMANIZATION_PROMPT;
            case SOURCE -> null;
        };
    }

    /**
     * Switches the provider on only after it answered; an unusable key fails silently at playback.
     */
    private void enableAfterValidation(Item item, Switch switchV, TextView name) {
        final int fg = ThemeUtils.getAppForegroundColor();
        Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_validating"));
        LyricsProviderConfigDialog.validate(item.id, valid -> {
            if (!isEnabled()) {
                return;
            }
            // Only a failure has to undo the switch, which stayed on while the request ran.
            if (!valid) {
                switchV.setChecked(false);
            }
            item.enabled = valid;
            name.setTextColor(valid ? fg : withAlpha(fg, 0x66));
            saveItems();
            updateSummary();
            if (!valid) {
                Utils.showToastShort(str("morphe_music_lyrics_ai_config_status_invalid"));
            }
        });
    }

    /**
     * Local needs its dictionary before it can romanize anything. It is asked for first and only
     * switched on once downloading has started, so a refused download never leaves it enabled.
     */
    private void enableLocal(Item item, Switch switchV, TextView name,
                             Runnable onEnabled, Runnable onRefused) {
        final boolean[] chosen = LyricsLocalRomanizationDownloads.chosen();
        if (!chosen[LyricsLocalRomanizationDownloads.SUDACHI]
                && !chosen[LyricsLocalRomanizationDownloads.UNIDICT]
                && !chosen[LyricsLocalRomanizationDownloads.PINYIN]
                && !chosen[LyricsLocalRomanizationDownloads.CLDR]) {
            // Read by rule alone, so there is nothing to ask for.
            enableRow(switchV, name);
            onEnabled.run();
            return;
        }
        if (LyricsLocalRomanizationDownloads.isReady()) {
            enableRow(switchV, name);
            onEnabled.run();
            LyricsLocalRomanizationDownloads.ensure();
            return;
        }

        final Context context = getContext();
        if (!(context instanceof android.app.Activity activity)
                || activity.isFinishing() || activity.isDestroyed()) {
            onRefused.run();
            return;
        }
        showDictionaryDialog(context, switchV, name, chosen, onEnabled, onRefused);
    }

    /** The name opens the same chooser the switch asks with, without turning the row on. */
    private void showLocalSettings() {
        final Context context = getContext();
        if (!(context instanceof android.app.Activity activity)
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        showDictionaryDialog(context, null, null, LyricsLocalRomanizationDownloads.chosen(),
                () -> { }, () -> { });
    }

    /**
     * The Rime word lists are this provider's settings. Opening them never turns the row on: a
     * list ticked while the provider is off waits for it.
     */
    private void showRimeSettings() {
        final Context context = getContext();
        if (!(context instanceof android.app.Activity activity)
                || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        LyricsRimeDictDialog.show(context, () -> {
            // A ticked table for the language above turns the row on (checklist §4.1),
            // the same background-fetch + toast model the Local dictionary uses.
            turnOnRimeIfTable();
            turnOffRimeWithoutTable();
            updateSummary();
        }, null);
    }

    /** The name-tap Save may have ticked a table for the language above; turn the row on
     *  beside that fetch rather than leaving it off until the user taps the switch. */
    private void turnOnRimeIfTable() {
        for (Item item : items) {
            if (isRimeProvider(item.id) && !item.enabled && RimeDictStore.isActive()) {
                item.enabled = true;
                saveItems();
                rebuildRows();
                return;
            }
        }
    }

    /** A Save can untick the only list the chosen language reads; turn the row off rather than
     *  leave it on to return nothing, and toast why. */
    private void turnOffRimeWithoutTable() {
        for (Item item : items) {
            if (isRimeProvider(item.id) && item.enabled && !RimeDictStore.isActive()) {
                item.enabled = false;
                saveItems();
                rebuildRows();
                Utils.showToastShort(str("morphe_music_lyrics_rime_dict_no_table"));
                return;
            }
        }
    }

    /**
     * Rime Wordlist reads Han through the word table of the language picked above, so it can only be
     * on for a language that has one. A table that is ticked but not built yet is fetched as the
     * row turns on.
     *
     * <p>With no table ticked the switch opens the list instead of refusing, the same way the
     * Local row asks which dictionaries to fetch. The row is only saved on once a Save left a
     * table for the chosen language.
     */
    private void enableRime(Switch switchV, TextView name, Runnable onEnabled, Runnable onRefused) {
        if (!RimeDictStore.isActive()) {
            final Context context = getContext();
            if (!(context instanceof android.app.Activity activity)
                    || activity.isFinishing() || activity.isDestroyed()) {
                onRefused.run();
                return;
            }
            final boolean[] turnedOn = {false};
            LyricsRimeDictDialog.show(context, () -> {
                if (turnedOn[0] || !RimeDictStore.isActive()) {
                    return;
                }
                // The dialog's Save already fetches every ticked table, this language's
                // included, so the row is turned on beside that fetch rather than by another.
                turnedOn[0] = true;
                enableRow(switchV, name);
                onEnabled.run();
            }, () -> {
                if (turnedOn[0]) {
                    return;
                }
                onRefused.run();
                // Closed without a Save, or saved with nothing ticked for the language above.
                Utils.showToastShort(str("morphe_music_lyrics_rime_dict_no_table"));
            });
            return;
        }
        enableRow(switchV, name);
        onEnabled.run();
        Utils.runOnBackgroundThread(
                () -> RimeDictStore.ensureForProvider(RimeDictStore.SILENT));
    }

    private static void enableRow(Switch switchV, TextView name) {
        switchV.setChecked(true);
        name.setTextColor(ThemeUtils.getAppForegroundColor());
    }

    /**
     * Asks which readings to fetch, then shows their progress in the same dialog. Closing it stops
     * the progress updates; the download itself ends with a toast either way.
     *
     * @param switchV null when opened from the name rather than from the switch.
     */
    private void showDictionaryDialog(Context context, @Nullable Switch switchV,
                                      @Nullable TextView name, boolean[] chosen,
                                      Runnable onEnabled, Runnable onRefused) {
        final int fg = ThemeUtils.getAppForegroundColor();

        final TextView hint = new TextView(context);
        hint.setTextSize(14);
        hint.setTextColor(withAlpha(fg, 0x99));
        hint.setText(str("morphe_music_lyrics_local_romanization_summary"));
        hint.setPadding(0, 0, 0, Dim.dp12);

        final CheckBox japanese = new CheckBox(context);
        japanese.setText(str("morphe_music_lyrics_local_romanization_dict_japanese"));
        japanese.setChecked(chosen[LyricsLocalRomanizationDownloads.SUDACHI]);
        styleCheckBox(japanese, fg);

        final CheckBox unidict = new CheckBox(context);
        unidict.setText(str("morphe_music_lyrics_local_romanization_dict_unidict"));
        unidict.setChecked(chosen[LyricsLocalRomanizationDownloads.UNIDICT]);
        styleCheckBox(unidict, fg);

        final CheckBox chinese = new CheckBox(context);
        chinese.setText(str("morphe_music_lyrics_local_romanization_dict_chinese"));
        chinese.setChecked(chosen[LyricsLocalRomanizationDownloads.PINYIN]);
        styleCheckBox(chinese, fg);

        final CheckBox cldr = new CheckBox(context);
        cldr.setText(str("morphe_music_lyrics_local_romanization_dict_cldr"));
        cldr.setChecked(chosen[LyricsLocalRomanizationDownloads.CLDR]);
        styleCheckBox(cldr, fg);

        // A Chinese word table reads the rest of a line from the character tables, so choosing
        // one keeps the character provider on.
        final CompoundButton.OnCheckedChangeListener keepUniDict = (button, checked) -> {
            if (checked) {
                unidict.setChecked(true);
            }
        };
        chinese.setOnCheckedChangeListener(keepUniDict);

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(hint);
        content.addView(japanese);
        content.addView(unidict);
        content.addView(chinese);
        content.addView(cldr);

        // Attached here rather than on the dialog so a close in any way detaches it. A watched
        // sink also survives a rebuild: only detach takes it away.
        final LyricsLocalRomanizationDownloads.ProgressSink sink = hint::setText;
        // Set once the user agrees: the dismiss listener then knows the choice was saved, so
        // closing must not put the row back. Save closes right away; the download runs in the
        // background and answers with a toast.
        final boolean[] accepted = {false};

        final Pair<Dialog, LinearLayout> pair = CustomDialog.create(context,
                str("morphe_music_lyrics_local_romanization_download_confirm_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> { },
                () -> { },
                null,
                null,
                false);

        final Dialog dialog = pair.first;
        final LinearLayout mainLayout = pair.second;
        final android.widget.ScrollView scrollView = new android.widget.ScrollView(context);
        scrollView.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.bottomMargin = Dim.dp12;
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1, scrollParams);

        // Save writes the choice and closes; the download runs in the background and answers with
        // a toast. Cancel closes without changing anything, and the dismiss listener puts the row
        // back if the dialog went away without an agreed choice.
        final Button ok = button(mainLayout, str("morphe_settings_save"));
        ok.setOnClickListener(v -> {
            if (!japanese.isChecked() && !unidict.isChecked()
                    && !chinese.isChecked() && !cldr.isChecked()) {
                Utils.showToastShort(str("morphe_music_lyrics_local_romanization_dict_empty"));
                return;
            }
            LyricsLocalRomanizationDownloads.saveChoice(
                    japanese.isChecked(), unidict.isChecked(),
                    chinese.isChecked(), cldr.isChecked());
            accepted[0] = true;
            if (switchV == null) {
                // Opened from the name, so only a row that is already on fetches what is missing.
                dialog.dismiss();
                if (isLocalEnabled()) {
                    LyricsLocalRomanizationDownloads.resetForUserDownload();
                    LyricsLocalRomanizationDownloads.ensure();
                }
                return;
            }
            dialog.dismiss();
            LyricsLocalRomanizationDownloads.resetForUserDownload();
            enableRow(switchV, name);
            // Started before the row is saved, so the "dictionaries are missing" repair that
            // save runs does not turn the row back off while its download is running.
            LyricsLocalRomanizationDownloads.ensure();
            onEnabled.run();
        });

        dialog.setOnDismissListener(d -> {
            LyricsLocalRomanizationDownloads.detach(sink);
            // Closing without agreeing never became a choice, so the row is put back and the
            // list is left exactly as it was.
            if (!accepted[0]) {
                onRefused.run();
            }
        });

        LyricsLocalRomanizationDownloads.attach(sink);
        dialog.show();
        final CharSequence running = LyricsLocalRomanizationDownloads.progressHint();
        if (running != null) {
            // A download already under way: show it, but a choice is still what starts it again.
            hint.setText(running);
        }
    }

    /** Found by label because the button row is reordered when the labels do not fit. */
    private static Button button(ViewGroup layout, CharSequence text) {
        for (int i = 0; i < layout.getChildCount(); i++) {
            final View child = layout.getChildAt(i);
            if (child instanceof Button
                    && text.toString().contentEquals(((Button) child).getText())) {
                return (Button) child;
            }
            if (child instanceof ViewGroup) {
                final Button found = button((ViewGroup) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private final List<Item> items = new ArrayList<>();
    private LinearLayout rowsContainer;
    private int dragIndex = -1;
    private boolean dragReordered = false;

    public LyricsOrderedListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsOrderedListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsOrderedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsOrderedListPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(true);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        if (!isEnabled()) {
            return;
        }
        try {
            CustomDialog.create(getContext(),
                    str("morphe_music_lyrics_source_reset_title"),
                    str("morphe_music_lyrics_source_reset_message"),
                    null,
                    null,
                    () -> {
                        providerSetting().resetToDefault();
                        if (isLyricsSource()) {
                            Settings.LYRICS_QUICKLRC_ENABLED.resetToDefault();
                        }
                        loadItems();
                        updateSummary();
                        rebuildRows();
                    },
                    () -> { },
                    null,
                    null,
                    true).first.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the reset dialog", ex);
        }
    }

    @Override
    protected void onAttachedToHierarchy(android.preference.PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        // Keep the summary correct before the first draw.
        try {
            loadItems();
            updateSummary();
        } catch (Exception ex) {
            Logger.printDebug(() -> "onAttachedToHierarchy failure", ex);
        }
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        try {
            return buildView(convertView, parent);
        } catch (Throwable ignored) {
            // A failure here would take down the whole settings screen.
            LinearLayout fallback = new LinearLayout(getContext());
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return fallback;
        }
    }

    private View buildView(View convertView, ViewGroup parent) {
        loadItems();
        updateSummary();
        Context context = getContext();
        final int fg = ThemeUtils.getAppForegroundColor();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Dim.dp20, 0, Dim.dp20, 0);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnClickListener(v -> {
            if (isEnabled()) {
                onClick();
            }
        });

        CharSequence title = getTitle();
        if (!TextUtils.isEmpty(title)) {
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextSize(16);
            titleView.setTextColor(fg);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tp.topMargin = Dim.dp4;
            root.addView(titleView, tp);
        }

        CharSequence summary = getSummary();
        if (!TextUtils.isEmpty(summary)) {
            TextView summaryView = new TextView(context);
            summaryView.setText(summary);
            summaryView.setTextSize(13);
            summaryView.setTextColor(withAlpha(fg, 0x99));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.topMargin = Dim.dp4;
            root.addView(summaryView, sp);
        }

        rowsContainer = new LinearLayout(context);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(rowsContainer);

        rebuildRows();

        return root;
    }

    /** How many providers are on; a service has to keep at least one. */
    private int enabledCount() {
        int count = 0;
        for (Item item : items) {
            if (item.enabled) {
                count++;
            }
        }
        return count;
    }

    /**
     * Puts a provider back on when a stored list, or a provider whose settings were cleared, left
     * the service with nothing. The switch refuses to turn the last one off, so this only repairs
     * a list written before that rule existed. Sources are left alone: reading no lyrics at all is
     * a state the source list offers on purpose.
     */
    private void keepOneEnabled() {
        if (isLyricsSource() || enabledCount() > 0) {
            return;
        }
        for (Item item : items) {
            // Embedded and Google are always in the list and never need setting up, so one of
            // them answers here; an unconfigured row would only be turned off again on saving.
            if (!isUnconfigured(item.id)) {
                item.enabled = true;
                return;
            }
        }
        // Unreachable while those two are in every order, but the list must never end up empty.
        if (!items.isEmpty()) {
            items.get(0).enabled = true;
        }
    }

    /** Whether a provider that needs setting up would still fail if it were on. */
    private boolean isUnconfigured(String id) {
        if (kind() == Kind.TRANSLATION && AiProviders.DEEPL.equals(id)) {
            return Settings.LYRICS_DEEPL_API_KEY.get().isBlank();
        }
        return switch (id) {
            case AiProviders.OPENAI_COMPATIBLE ->
                    Settings.LYRICS_AI_BASE_URL.get().isBlank()
                            || Settings.LYRICS_AI_MODEL.get().isBlank();
            default -> false;
        };
    }

    private static boolean isTokenRequired(String id) {
        StringSetting setting = switch (id) {
            case "Captions" -> Settings.LYRICS_CAPTION_COOKIES;
            case "Spotify" -> Settings.SPOTIFY_TOKEN;
            case "Deezer" -> Settings.DEEZER_ARL;
            default -> null;
        };
        return setting != null && setting.get().isBlank();
    }

    private static boolean hasTokenDialog(String id) {
        return switch (id) {
            case "Captions", "Apple", "Spotify", "Deezer", "Musixmatch" -> true;
            default -> false;
        };
    }

    private void showTokenDialogFor(String id, Runnable onTokenSaved) {
        Context context = getContext();
        LyricsTokenDialogPreference pref;
        switch (id) {
            case "Captions":    pref = LyricsTokenDialogPreference.youtube(context); break;
            case "Apple":       pref = LyricsTokenDialogPreference.apple(context); break;
            case "Spotify":     pref = LyricsTokenDialogPreference.spotify(context); break;
            case "Deezer":      pref = LyricsTokenDialogPreference.deezer(context); break;
            case "Musixmatch":  pref = LyricsTokenDialogPreference.musixmatch(context); break;
            default: return;
        }
        pref.showDialog(onTokenSaved);
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to open provider site", ex);
        }
    }

    private void rebuildRows() {
        if (rowsContainer == null) {
            return;
        }
        rowsContainer.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            rowsContainer.addView(createRow(items.get(i), i));
        }
        if (isLyricsSource()) {
            rowsContainer.addView(createQuickLrcRow());
        }
    }

    /**
     * QuickLRC gives the original lyrics rather than a lookup source, so it sits below every
     * source and keeps no position of its own: it only records whether the service may be used.
     */
    private View createQuickLrcRow() {
        Context context = getContext();
        final int fg = ThemeUtils.getAppForegroundColor();
        final boolean locked = !isEnabled();
        final boolean enabled = Settings.LYRICS_QUICKLRC_ENABLED.get();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Dim.dp8, 0, Dim.dp8);

        row.addView(createGripSpacer(context));

        TextView name = new TextView(context);
        name.setText(providerLabel(QUICKLRC));
        name.setTextSize(16);
        name.setTextColor(locked ? withAlpha(fg, 0x44) : (enabled ? fg : withAlpha(fg, 0x66)));
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!locked) {
            name.setClickable(true);
            name.setOnClickListener(v -> LyricsTranscribeKeyPreference.showKeyDialog(
                    context, () -> rebuildRows()));
        }

        Switch switchV = new Switch(context);
        switchV.setChecked(enabled);
        switchV.setEnabled(!locked);
        switchV.setPadding(Dim.dp8, 0, 0, 0);
        if (!locked) {
            switchV.setOnCheckedChangeListener((v, checked) -> {
                if (!checked) {
                    Settings.LYRICS_QUICKLRC_ENABLED.save(false);
                    name.setTextColor(withAlpha(fg, 0x66));
                    return;
                }
                if (isQuickLrcKeyMissing()) {
                    switchV.setChecked(false);
                    LyricsTranscribeKeyPreference.showKeyDialog(context, () -> {
                        if (isQuickLrcKeyMissing()) {
                            return;
                        }
                        Settings.LYRICS_QUICKLRC_ENABLED.save(true);
                        rebuildRows();
                    });
                    return;
                }
                Settings.LYRICS_QUICKLRC_ENABLED.save(true);
                name.setTextColor(fg);
            });
        }

        row.addView(name);
        row.addView(switchV);
        return row;
    }

    /** Holds the grip's place on a row that cannot be dragged, at the same width. */
    private static View createGripSpacer(Context context) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(GRIP_COLUMN, 1));
        return spacer;
    }

    private View createRow(Item item, int itemsIndex) {
        Context context = getContext();
        final int fg = ThemeUtils.getAppForegroundColor();
        final boolean locked = !isEnabled();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Dim.dp8, 0, Dim.dp8);
        // A pinned row starts no drag at all, rather than one that snaps back.
        final boolean draggable = !locked && !isPinnedRow(itemsIndex);
        row.setLongClickable(draggable);
        row.setTag(itemsIndex);

        final View grip;
        if (draggable) {
            TextView handle = new TextView(context);
            handle.setText("⠿");
            // An icon, so it keeps its size when the system font changes and the
            // fixed column never has to grow with it.
            handle.setTextSize(TypedValue.COMPLEX_UNIT_PX, Dim.dp20);
            handle.setGravity(Gravity.CENTER);
            handle.setTextColor(withAlpha(fg, 0xAA));
            handle.setLayoutParams(new LinearLayout.LayoutParams(
                    GRIP_COLUMN, LinearLayout.LayoutParams.WRAP_CONTENT));
            grip = handle;
        } else {
            grip = createGripSpacer(context);
        }

        TextView name = new TextView(context);
        name.setText(providerLabel(item.id));
        name.setTextSize(16);
        name.setTextColor(locked ? withAlpha(fg, 0x44) : (item.enabled ? fg : withAlpha(fg, 0x66)));
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!locked && isLyricsSource() && hasTokenDialog(item.id)) {
            name.setClickable(true);
            name.setOnClickListener(v -> showTokenDialogFor(item.id, null));
        } else if (!locked && isLyricsSource() && PROVIDER_URLS.containsKey(item.id)) {
            final String url = PROVIDER_URLS.get(item.id);
            name.setClickable(true);
            name.setOnClickListener(v -> openUrl(url));
        } else if (!locked && isLocalProvider(item.id)) {
            name.setClickable(true);
            name.setOnClickListener(v -> showLocalSettings());
        } else if (!locked && isRimeProvider(item.id)) {
            name.setClickable(true);
            name.setOnClickListener(v -> showRimeSettings());
        } else if (!locked && hasConfigDialog(item.id)) {
            name.setClickable(true);
            name.setOnClickListener(v -> showConfigDialog(item.id));
        }

        Switch switchV = new Switch(context);
        switchV.setChecked(item.enabled);
        switchV.setEnabled(!locked);
        switchV.setPadding(Dim.dp8, 0, 0, 0);
        if (!locked) {
            switchV.setOnCheckedChangeListener((v, checked) -> {
                if (checked && isLyricsSource() && isTokenRequired(item.id)) {
                    switchV.setChecked(false);
                    showTokenDialogFor(item.id, () -> {
                        if (isTokenRequired(item.id)) {
                            item.enabled = false;
                            switchV.setChecked(false);
                            name.setTextColor(withAlpha(fg, 0x66));
                        } else {
                            item.enabled = true;
                            switchV.setChecked(true);
                            name.setTextColor(fg);
                        }
                        saveItems();
                        updateSummary();
                    });
                    return;
                }
                if (checked && hasConfigDialog(item.id)) {
                    // Left on until the answer arrives; moving the widget back here would
                    // re-enter this listener before the provider was asked.
                    item.enabled = true;
                    name.setTextColor(fg);
                    enableAfterValidation(item, switchV, name);
                    return;
                }
                if (checked && isLocalProvider(item.id)) {
                    // The download dialog decides whether the switch stays on, so the row is
                    // saved only once it closes.
                    enableLocal(item, switchV, name, () -> {
                        item.enabled = true;
                        saveItems();
                        updateSummary();
                    }, () -> {
                        item.enabled = false;
                        saveItems();
                        updateSummary();
                        switchV.setChecked(false);
                    });
                    return;
                }
                if (checked && isRimeProvider(item.id)) {
                    enableRime(switchV, name, () -> {
                        item.enabled = true;
                        saveItems();
                        updateSummary();
                    }, () -> {
                        item.enabled = false;
                        saveItems();
                        updateSummary();
                        switchV.setChecked(false);
                    });
                    return;
                }
                if (!checked && item.enabled && !isLyricsSource() && enabledCount() <= 1) {
                    // A service with nothing on has no way to translate or romanize, so the
                    // last provider cannot be turned off.
                    switchV.setChecked(true);
                    Utils.showToastShort(str("morphe_music_lyrics_providers_keep_one"));
                    return;
                }
                item.enabled = checked;
                name.setTextColor(checked ? fg : withAlpha(fg, 0x66));
                saveItems();
                updateSummary();
            });
        }

        row.addView(grip);
        row.addView(name);
        row.addView(switchV);

        if (draggable) {
            row.setOnLongClickListener(v -> {
                dragIndex = (int) v.getTag();
                dragReordered = false;
                v.setAlpha(0.4f);
                v.startDragAndDrop(ClipData.newPlainText("", ""),
                        new View.DragShadowBuilder(v), null, 0);
                return true;
            });
            row.setOnDragListener((v, event) -> {
                switch (event.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return true;
                    case DragEvent.ACTION_DRAG_LOCATION: {
                        final int target = (int) v.getTag();
                        if (target != dragIndex && target >= 0) {
                            final int from = dragIndex;
                            // The hovered row is the destination; an insertion boundary made the
                            // last row unreachable.
                            reorder(from, target);
                            moveRow(from, target);
                            dragIndex = target;
                        }
                        return true;
                    }
                    case DragEvent.ACTION_DRAG_ENDED:
                        for (int i = 0, n = rowsContainer.getChildCount(); i < n; i++) {
                            rowsContainer.getChildAt(i).setAlpha(1f);
                        }
                        if (dragReordered) {
                            saveItems();
                            dragReordered = false;
                            updateSummary();
                        }
                        dragIndex = -1;
                        return true;
                    default:
                        return false;
                }
            });
        }

        return row;
    }

    private void moveRow(int from, int to) {
        final int expected = items.size() + (isLyricsSource() ? 1 : 0);
        if (rowsContainer == null || rowsContainer.getChildCount() != expected) {
            rebuildRows();
            return;
        }
        if (from == to || from < 0 || from >= rowsContainer.getChildCount()) {
            return;
        }
        View row = rowsContainer.getChildAt(from);
        rowsContainer.removeViewAt(from);
        rowsContainer.addView(row, Math.max(0, Math.min(to, rowsContainer.getChildCount())));
        for (int i = 0; i < rowsContainer.getChildCount(); i++) {
            rowsContainer.getChildAt(i).setTag(i);
        }
    }

    /** The first entry of a translation or romanization list is pinned and never moves. */
    private boolean isPinnedRow(int index) {
        return index == 0 && !isLyricsSource();
    }

    private void reorder(int from, int to) {
        if (from == to || from < 0 || to < 0
                || from >= items.size() || to >= items.size()) {
            return;
        }
        if (isPinnedRow(from) || isPinnedRow(to)) {
            return;
        }
        Item moved = items.remove(from);
        items.add(to, moved);
        dragReordered = true;
    }

    private void loadItems() {
        items.clear();
        String stored = providerSetting().get();
        if (isLyricsSource() && (stored == null || !stored.contains(","))) {
            // Match LyricsProviders, which treats a legacy single source as the default order.
            stored = Settings.DEFAULT_LYRICS_ORDER;
        }
        for (AiProviders.ProviderEntry entry : AiProviders.parseOrder(stored, providerOrder())) {
            items.add(new Item(entry.id(), entry.enabled()));
        }

        // A list stored before the "at least one" rule, or one whose only provider has since lost
        // its settings, is repaired on the way in rather than shown with nothing on.
        final boolean turnedOff = disableTokenlessItems();
        if (turnedOff || (!isLyricsSource() && enabledCount() == 0)) {
            saveItems();
        }
    }

    private boolean isLocalEnabled() {
        for (Item item : items) {
            if (isLocalProvider(item.id) && item.enabled) {
                return true;
            }
        }
        return false;
    }

    /**
     * A provider that cannot answer is turned off rather than left on to fail at every playback.
     * For sources a missing token counts as unconfigured; for the other lists it is the settings
     * its dialog holds. Local is turned off when its dictionaries are not all there: the reading
     * it would give would be missing whole scripts, and nothing is downloaded here, so an off row
     * is the honest state until the user fetches them from the switch. A fetch that is already
     * running is not cut short; it was asked for and gets to finish.
     */
    private boolean disableTokenlessItems() {
        boolean changed = false;
        for (Item item : items) {
            if (!item.enabled) {
                continue;
            }
            if (isLyricsSource() ? isTokenRequired(item.id) : isUnconfigured(item.id)) {
                item.enabled = false;
                changed = true;
                continue;
            }
            if (isLocalProvider(item.id) && !LyricsLocalRomanizationDownloads.isReady()
                    && !LyricsLocalRomanizationDownloads.isDownloading()) {
                item.enabled = false;
                changed = true;
                continue;
            }
            // Rime Wordlist reads through the word list of the language above, so a row with no table
            // is turned off rather than left on to return an empty line.
            if (isRimeProvider(item.id) && !RimeDictStore.isActive()) {
                item.enabled = false;
                changed = true;
            }
        }
        return changed;
    }

    private void saveItems() {
        disableTokenlessItems();
        keepOneEnabled();
        List<AiProviders.ProviderEntry> entries = new ArrayList<>(items.size());
        for (Item item : items) {
            entries.add(new AiProviders.ProviderEntry(item.id, item.enabled));
        }
        providerSetting().save(AiProviders.serializeOrder(entries));
    }

    private void updateSummary() {
        List<String> enabled = new ArrayList<>();
        for (Item item : items) {
            if (item.enabled && !(isLyricsSource() && isTokenRequired(item.id))) {
                enabled.add(providerLabel(item.id));
            }
        }
        if (isLyricsSource() && Settings.LYRICS_QUICKLRC_ENABLED.get()) {
            enabled.add(providerLabel(QUICKLRC));
        }
        if (enabled.isEmpty()) {
            // Only a source list can be empty here: the other lists keep one provider on.
            setSummary(str("morphe_music_lyrics_source_all_disabled"));
        } else {
            setSummary(TextUtils.join(" > ", enabled));
        }
    }
}
