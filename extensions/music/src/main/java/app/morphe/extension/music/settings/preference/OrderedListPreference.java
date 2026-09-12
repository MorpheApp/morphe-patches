/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.graphics.Color;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Preference that shows the third-party lyrics providers inline on the settings screen and lets the
 * user reorder them by long-pressing a row and dragging it, and toggle each one on or off with the
 * switch on the right. The order is stored as a comma separated list of provider ids
 * (their {@code LyricsProvider#name()} value) in {@link Settings#LYRICS_SOURCE}, with a leading
 * {@code '-'} marking a disabled provider.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class OrderedListPreference extends Preference {

    /** Canonical provider ids, in the default priority order, shown in the list. */
    private static final List<String> PROVIDER_ORDER = Arrays.asList(
            "Captions", "LRCLIB", "LyricallyApple", "QQ", "NetEase", "KuGou", "Luna", "bLyrics", "BiniLyrics",
            "Unison", "AMLL", "Apple", "Musixmatch", "Spotify", "Deezer");

    /** Friendlier labels for display; ids not present here are shown verbatim. */
    private static final Map<String, String> PROVIDER_LABELS = new HashMap<>();
    static {
        PROVIDER_LABELS.put("Captions", "YouTube Captions (Auto) *");
        PROVIDER_LABELS.put("LyricallyApple", "Apple Music (via Lyrically)");
        PROVIDER_LABELS.put("Apple", "Apple Music *");
        PROVIDER_LABELS.put("bLyrics", "BetterLyrics (bLyrics)");
        PROVIDER_LABELS.put("Luna", "Soda (Luna)");
        PROVIDER_LABELS.put("Musixmatch", "Musixmatch *");
        PROVIDER_LABELS.put("Spotify", "Spotify *");
        PROVIDER_LABELS.put("Deezer", "Deezer *");
    }

    private static String providerLabel(String id) {
        final String label = PROVIDER_LABELS.get(id);
        return label != null ? label : id;
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

    private final List<Item> items = new ArrayList<>();
    private LinearLayout rowsContainer;
    private int dragIndex = -1;
    private boolean dragReordered = false;

    public OrderedListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public OrderedListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public OrderedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OrderedListPreference(Context context) {
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
        new AlertDialog.Builder(getContext())
                .setTitle(str("morphe_music_lyrics_source_reset_title"))
                .setMessage(str("morphe_music_lyrics_source_reset_message"))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Settings.LYRICS_SOURCE.resetToDefault();
                    loadItems();
                    updateSummary();
                    rebuildRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onAttachedToHierarchy(android.preference.PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        setTitle(null);
        // Load eagerly so the summary is correct before the row is first drawn.
        try {
            loadItems();
            updateSummary();
        } catch (Exception ignored) {
        }
    }

    @Override
    public View getView(View convertView, ViewGroup parent) {
        loadItems();
        final Context context = getContext();
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
        if (title != null && title.length() > 0) {
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTextSize(16);
            titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            titleView.setTextColor(fg);
            root.addView(titleView);
        }

        CharSequence summary = getSummary();
        if (summary != null && summary.length() > 0) {
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

    private static boolean isTokenRequired(String id) {
        return (id.equals("Captions") && Settings.LYRICS_CAPTION_COOKIES.get().isBlank())
                || (id.equals("Apple") && Settings.APPLE_MUSIC_TOKEN.get().isBlank())
                || (id.equals("Spotify") && Settings.SPOTIFY_TOKEN.get().isBlank())
                || (id.equals("Deezer") && Settings.DEEZER_ARL.get().isBlank())
                || (id.equals("Musixmatch") && Settings.MUSIXMATCH_TOKEN.get().isBlank());
    }

    private static boolean hasTokenDialog(String id) {
        return id.equals("Captions") || id.equals("Apple")
                || id.equals("Spotify") || id.equals("Deezer")
                || id.equals("Musixmatch");
    }

    private void showTokenDialogFor(String id, Runnable onTokenSaved) {
        Context context = getContext();
        TokenDialogPreference pref;
        switch (id) {
            case "Captions":    pref = TokenDialogPreference.youtube(context); break;
            case "Apple":       pref = TokenDialogPreference.apple(context); break;
            case "Spotify":     pref = TokenDialogPreference.spotify(context); break;
            case "Deezer":      pref = TokenDialogPreference.deezer(context); break;
            case "Musixmatch":  pref = TokenDialogPreference.musixmatch(context); break;
            default: return;
        }
        pref.showDialog(onTokenSaved);
    }

    private void rebuildRows() {
        if (rowsContainer == null) {
            return;
        }
        rowsContainer.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            rowsContainer.addView(createRow(items.get(i), i));
        }
    }

    private View createRow(Item item, int itemsIndex) {
        final Context context = getContext();
        final int fg = ThemeUtils.getAppForegroundColor();
        final boolean locked = !isEnabled();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Dim.dp8, 0, Dim.dp8);
        row.setLongClickable(!locked);
        row.setTag(itemsIndex);

        TextView grip = new TextView(context);
        grip.setText("\u283F");
        grip.setTextSize(20);
        grip.setTypeface(android.graphics.Typeface.DEFAULT);
        grip.setTextColor(withAlpha(fg, locked ? 0x44 : 0xAA));
        grip.setPadding(0, 0, Dim.dp8, 0);

        TextView name = new TextView(context);
        name.setText(providerLabel(item.id));
        name.setTextSize(16);
        name.setTextColor(locked ? withAlpha(fg, 0x44) : (item.enabled ? fg : withAlpha(fg, 0x66)));
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!locked && hasTokenDialog(item.id)) {
            name.setClickable(true);
            name.setOnClickListener(v -> showTokenDialogFor(item.id, null));
        }

        Switch switchV = new Switch(context);
        switchV.setChecked(item.enabled);
        switchV.setEnabled(!locked);
        switchV.setPadding(Dim.dp8, 0, 0, 0);
        if (!locked) {
            switchV.setOnCheckedChangeListener((v, checked) -> {
                if (checked && isTokenRequired(item.id)) {
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
                item.enabled = checked;
                name.setTextColor(checked ? fg : withAlpha(fg, 0x66));
                saveItems();
                updateSummary();
            });
        }

        row.addView(grip);
        row.addView(name);
        row.addView(switchV);

        if (!locked) {
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
                            reorder(dragIndex, target);
                            dragIndex = target;
                            rebuildRows();
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

    private void reorder(int from, int to) {
        if (from == to || from < 0 || to < 0) {
            return;
        }
        final Item moved = items.remove(from);
        items.add(from < to ? to - 1 : to, moved);
        dragReordered = true;
    }

    private void loadItems() {
        items.clear();
        final String stored = Settings.LYRICS_SOURCE.get();
        final Set<String> seen = new HashSet<>();

        if (stored != null && stored.contains(",")) {
            for (String raw : stored.split(",")) {
                String token = raw.trim();
                if (token.isEmpty()) {
                    continue;
                }
                boolean enabled = true;
                if (token.startsWith("-")) {
                    enabled = false;
                    token = token.substring(1).trim();
                }
                if (!PROVIDER_ORDER.contains(token) || seen.contains(token)) {
                    continue;
                }
                seen.add(token);
                items.add(new Item(token, enabled));
            }
        }

        // Show every provider not yet loaded.
        for (String id : PROVIDER_ORDER) {
            if (!seen.contains(id)) {
                items.add(new Item(id, true));
            }
        }

        boolean changed = false;
        for (Item item : items) {
            if (item.enabled && isTokenRequired(item.id)) {
                item.enabled = false;
                changed = true;
            }
        }
        if (changed) {
            saveItems();
        }
    }

    private void saveItems() {
        for (Item item : items) {
            if (item.enabled && isTokenRequired(item.id)) {
                item.enabled = false;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Item item : items) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            if (!item.enabled) {
                sb.append('-');
            }
            sb.append(item.id);
        }
        Settings.LYRICS_SOURCE.save(sb.toString());
    }

    private void updateSummary() {
        List<String> enabled = new ArrayList<>();
        for (Item item : items) {
            if (item.enabled && !isTokenRequired(item.id)) {
                enabled.add(providerLabel(item.id));
            }
        }
        if (enabled.isEmpty()) {
            setSummary(str("morphe_music_lyrics_source_all_disabled"));
        } else {
            setSummary(android.text.TextUtils.join(" > ", enabled));
        }
    }
}
