package app.morphe.extension.music.sponsorblock.objects;

import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_FILLER;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_FILLER_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_HOOK;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_HOOK_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_INTERACTION;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_INTERACTION_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_INTRO;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_INTRO_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_MUSIC_OFFTOPIC;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_MUSIC_OFFTOPIC_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_OUTRO;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_OUTRO_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_PREVIEW;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_PREVIEW_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_SELF_PROMO;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_SELF_PROMO_COLOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_SPONSOR;
import static app.morphe.extension.music.settings.MusicSponsorBlockSettings.SB_CATEGORY_SPONSOR_COLOR;
import static app.morphe.extension.shared.StringRef.sf;

import android.graphics.Color;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

public enum SegmentCategory {
    SPONSOR("sponsor",
            sf("morphe_music_sb_segments_sponsor_title"), sf("morphe_music_sb_segments_sponsor_summary"),
            sf("morphe_music_sb_skipped_sponsor"),
            SB_CATEGORY_SPONSOR, SB_CATEGORY_SPONSOR_COLOR),
    SELF_PROMO("selfpromo",
            sf("morphe_music_sb_segments_selfpromo_title"), sf("morphe_music_sb_segments_selfpromo_summary"),
            sf("morphe_music_sb_skipped_selfpromo"),
            SB_CATEGORY_SELF_PROMO, SB_CATEGORY_SELF_PROMO_COLOR),
    INTERACTION("interaction",
            sf("morphe_music_sb_segments_interaction_title"), sf("morphe_music_sb_segments_interaction_summary"),
            sf("morphe_music_sb_skipped_interaction"),
            SB_CATEGORY_INTERACTION, SB_CATEGORY_INTERACTION_COLOR),
    INTRO("intro",
            sf("morphe_music_sb_segments_intro_title"), sf("morphe_music_sb_segments_intro_summary"),
            sf("morphe_music_sb_skipped_intro"),
            SB_CATEGORY_INTRO, SB_CATEGORY_INTRO_COLOR),
    OUTRO("outro",
            sf("morphe_music_sb_segments_outro_title"), sf("morphe_music_sb_segments_outro_summary"),
            sf("morphe_music_sb_skipped_outro"),
            SB_CATEGORY_OUTRO, SB_CATEGORY_OUTRO_COLOR),
    PREVIEW("preview",
            sf("morphe_music_sb_segments_preview_title"), sf("morphe_music_sb_segments_preview_summary"),
            sf("morphe_music_sb_skipped_preview"),
            SB_CATEGORY_PREVIEW, SB_CATEGORY_PREVIEW_COLOR),
    HOOK("hook",
            sf("morphe_music_sb_segments_hook_title"), sf("morphe_music_sb_segments_hook_summary"),
            sf("morphe_music_sb_skipped_hook"),
            SB_CATEGORY_HOOK, SB_CATEGORY_HOOK_COLOR),
    FILLER("filler",
            sf("morphe_music_sb_segments_filler_title"), sf("morphe_music_sb_segments_filler_summary"),
            sf("morphe_music_sb_skipped_filler"),
            SB_CATEGORY_FILLER, SB_CATEGORY_FILLER_COLOR),
    MUSIC_OFFTOPIC("music_offtopic",
            sf("morphe_music_sb_segments_nomusic_title"), sf("morphe_music_sb_segments_nomusic_summary"),
            sf("morphe_music_sb_skipped_nomusic"),
            SB_CATEGORY_MUSIC_OFFTOPIC, SB_CATEGORY_MUSIC_OFFTOPIC_COLOR);

    private static final SegmentCategory[] ALL = values();
    private static final Map<String, SegmentCategory> BY_KEY = new HashMap<>(ALL.length * 2);

    static {
        for (SegmentCategory cat : ALL) BY_KEY.put(cat.categoryKey, cat);
    }

    public final String categoryKey;
    public final StringRef title;
    public final StringRef description;
    public final StringRef skippedToastText;
    public final BooleanSetting enabled;
    public final StringSetting color;
    public final Paint paint = new Paint();

    public CategoryBehaviour getBehaviour() {
        return enabled.get() ? CategoryBehaviour.SKIP_AUTOMATICALLY : CategoryBehaviour.IGNORE;
    }

    SegmentCategory(String categoryKey,
                    StringRef title, StringRef description, StringRef skippedToastText,
                    BooleanSetting enabled, StringSetting color) {
        this.categoryKey = Objects.requireNonNull(categoryKey);
        this.title = Objects.requireNonNull(title);
        this.description = Objects.requireNonNull(description);
        this.skippedToastText = Objects.requireNonNull(skippedToastText);
        this.enabled = Objects.requireNonNull(enabled);
        this.color = Objects.requireNonNull(color);
        loadColor();
    }

    private void loadColor() {
        try {
            paint.setColor(Color.parseColor(color.get()));
            paint.setStrokeWidth(0);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to parse color for category: " + categoryKey, ex);
        }
    }

    public void reloadColor() {
        loadColor();
    }

    @Nullable
    public static SegmentCategory byCategoryKey(@NonNull String key) {
        return BY_KEY.get(key);
    }

    /**
     * Comma-separated list of enabled category keys for the SponsorBlock API request.
     */
    @NonNull
    public static String sponsorBlockAPIFetchCategories() {
        List<String> enabled = new ArrayList<>();
        for (SegmentCategory cat : ALL) {
            if (cat.getBehaviour() != CategoryBehaviour.IGNORE) {
                enabled.add("\"" + cat.categoryKey + "\"");
            }
        }
        if (enabled.isEmpty()) return "[]";
        return "[" + String.join(",", enabled) + "]";
    }

    public static void updateEnabledCategories() {
        for (SegmentCategory cat : ALL) {
            cat.reloadColor();
        }
    }
}
