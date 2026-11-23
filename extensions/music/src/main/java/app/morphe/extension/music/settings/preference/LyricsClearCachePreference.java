/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import androidx.annotation.Nullable;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.function.Consumer;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache.BucketStats;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache.CacheBucket;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache.CacheStats;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Displays total offline lyrics cache usage on the settings row and opens a sectioned dialog to
 * inspect and clear the Lyrics, Translation, Transliteration, AI section and temporary offset
 * caches, or all of them, without touching downloaded romanization dictionaries.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsClearCachePreference extends Preference {

    private boolean dialogOpen;

    public LyricsClearCachePreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LyricsClearCachePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LyricsClearCachePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LyricsClearCachePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Nothing is stored here, so a persisted value would only leave a key nothing reads.
        setPersistent(false);
        setSelectable(true);
        refreshSummary();
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        refreshSummary();
    }

    /** The size is measured off the main thread; the settings page reopens often enough that
     * a stale summary never shows for long. */
    private void refreshSummary() {
        Utils.runOnBackgroundThread(() -> {
            final CacheStats stats = LyricsCache.stats();
            Utils.runOnMainThread(() -> applyOuterSummary(stats));
        });
    }

    private void applyOuterSummary(CacheStats stats) {
        final BucketStats total = stats.total();
        setSummary(str("morphe_music_lyrics_cache_total_summary",
                megabytes(total.bytes()), total.items()));
    }

    @Override
    protected void onClick() {
        if (!isEnabled() || dialogOpen) {
            return;
        }
        final Context context = getContext();
        if (isContextInvalid(context)) {
            return;
        }
        dialogOpen = true;
        Utils.runOnBackgroundThread(() -> {
            final CacheStats stats = LyricsCache.stats();
            Utils.runOnMainThread(() -> showCacheDialog(getContext(), stats));
        });
    }

    private static boolean isContextInvalid(@Nullable Context context) {
        return context == null
                || (context instanceof Activity activity
                && (activity.isFinishing() || activity.isDestroyed()));
    }

    private void showCacheDialog(Context context, CacheStats initialStats) {
        if (isContextInvalid(context)) {
            dialogOpen = false;
            return;
        }
        try {
            final Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_music_lyrics_clear_cache_title"),
                    null,
                    null,
                    str("morphe_music_lyrics_cache_close_action"),
                    () -> { },
                    null,
                    str("morphe_music_lyrics_cache_clear_all_title"),
                    () -> { },
                    false
            );

            final Dialog dialog = dialogPair.first;
            dialog.setOnDismissListener(d -> dialogOpen = false);
            final LinearLayout mainLayout = dialogPair.second;

            final LinearLayout cardsContainer = new LinearLayout(context);
            cardsContainer.setOrientation(LinearLayout.VERTICAL);

            final CardViews lyricsCard = addBucketCard(context, cardsContainer,
                    str("morphe_music_lyrics_cache_lyrics_category_title"), true);
            final CardViews translationCard = addBucketCard(context, cardsContainer,
                    str("morphe_music_lyrics_cache_translation_category_title"), true);
            final CardViews transliterationCard = addBucketCard(context, cardsContainer,
                    str("morphe_music_lyrics_cache_transliteration_category_title"), true);
            final CardViews aiSectionCard = addBucketCard(context, cardsContainer,
                    str("morphe_music_lyrics_cache_ai_section_category_title"), true);
            final CardViews offsetCard = addBucketCard(context, cardsContainer,
                    str("morphe_music_lyrics_cache_offset_category_title"), true);
            final CardViews totalCard = addTotalCard(context, cardsContainer,
                    str("morphe_music_lyrics_cache_total_category_title"));

            final ScrollView scrollView = new ScrollView(context);
            scrollView.setVerticalScrollBarEnabled(false);
            scrollView.setOverScrollMode(ScrollView.OVER_SCROLL_NEVER);
            scrollView.addView(cardsContainer);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            mainLayout.addView(scrollView, Math.max(0, mainLayout.getChildCount() - 1), scrollParams);

            final Button clearAllBtn = ThemedInput.findButton(
                    mainLayout, str("morphe_music_lyrics_cache_clear_all_title"));

            final CacheStats[] currentStats = {initialStats};
            final boolean[] confirmOpen = {false};
            final boolean[] clearingInProgress = {false};
            final Runnable[] refreshUi = new Runnable[1];
            final Consumer<CacheStats> onCleared = updated -> {
                applyOuterSummary(updated);
                if (!dialog.isShowing() || isContextInvalid(context)) {
                    return;
                }
                currentStats[0] = updated;
                refreshUi[0].run();
            };
            refreshUi[0] = () -> {
                CacheStats s = currentStats[0];
                bindCard(lyricsCard, CacheBucket.LYRICS, s.lyrics(),
                        () -> confirmAndClear(context, CacheBucket.LYRICS,
                                currentStats[0], confirmOpen, clearingInProgress,
                                onCleared));
                bindCard(translationCard, CacheBucket.TRANSLATION, s.translation(),
                        () -> confirmAndClear(context, CacheBucket.TRANSLATION,
                                currentStats[0], confirmOpen, clearingInProgress,
                                onCleared));
                bindCard(transliterationCard, CacheBucket.TRANSLITERATION, s.transliteration(),
                        () -> confirmAndClear(context, CacheBucket.TRANSLITERATION,
                                currentStats[0], confirmOpen, clearingInProgress,
                                onCleared));
                bindCard(aiSectionCard, CacheBucket.AI_SECTION, s.aiSection(),
                        () -> confirmAndClear(context, CacheBucket.AI_SECTION,
                                currentStats[0], confirmOpen, clearingInProgress,
                                onCleared));
                bindCard(offsetCard, CacheBucket.OFFSET, s.offset(),
                        () -> confirmAndClear(context, CacheBucket.OFFSET,
                                currentStats[0], confirmOpen, clearingInProgress,
                                onCleared));

                BucketStats total = s.total();
                totalCard.summaryView.setText(str("morphe_music_lyrics_cache_total_summary",
                        megabytes(total.bytes()), total.items()));
                if (clearAllBtn != null) {
                    boolean hasAny = total.bytes() > 0 || total.items() > 0;
                    clearAllBtn.setEnabled(hasAny);
                    clearAllBtn.setAlpha(hasAny ? 1f : 0.45f);
                }
                applyOuterSummary(s);
            };

            if (clearAllBtn != null) {
                clearAllBtn.setOnClickListener(v -> confirmAndClear(context, CacheBucket.ALL,
                        currentStats[0], confirmOpen, clearingInProgress, onCleared));
            }

            refreshUi[0].run();
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            dialogOpen = false;
            Logger.printException(() -> "Could not show the clear cache dialog", ex);
        } catch (RuntimeException ex) {
            dialogOpen = false;
            throw ex;
        }
    }

    private void confirmAndClear(Context context, CacheBucket bucket, CacheStats stats,
                                 boolean[] confirmOpen, boolean[] clearingInProgress,
                                 Consumer<CacheStats> onCleared) {
        if (confirmOpen[0] || clearingInProgress[0] || isContextInvalid(context)) {
            return;
        }
        final BucketStats targetStats = stats.forBucket(bucket);
        if (targetStats.bytes() == 0 && targetStats.items() == 0) {
            return;
        }
        final String title = switch (bucket) {
            case LYRICS -> str("morphe_music_lyrics_cache_clear_lyrics_title");
            case TRANSLATION -> str("morphe_music_lyrics_cache_clear_translation_title");
            case TRANSLITERATION -> str("morphe_music_lyrics_cache_clear_transliteration_title");
            case AI_SECTION -> str("morphe_music_lyrics_cache_clear_ai_section_title");
            case OFFSET -> str("morphe_music_lyrics_cache_clear_offset_title");
            case ALL -> str("morphe_music_lyrics_cache_clear_all_title");
        };
        final String confirmKey = switch (bucket) {
            case LYRICS -> "morphe_music_lyrics_cache_confirm_lyrics";
            case TRANSLATION -> "morphe_music_lyrics_cache_confirm_translation";
            case TRANSLITERATION -> "morphe_music_lyrics_cache_confirm_transliteration";
            case AI_SECTION -> "morphe_music_lyrics_cache_confirm_ai_section";
            case OFFSET -> "morphe_music_lyrics_cache_confirm_offset";
            case ALL -> "morphe_music_lyrics_cache_confirm_all";
        };
        final String size = megabytes(targetStats.bytes());
        confirmOpen[0] = true;
        try {
            Dialog confirmDialog = CustomDialog.create(context,
                    title,
                    str(confirmKey, size, targetStats.items()),
                    null,
                    str("morphe_music_lyrics_cache_clear_action"),
                    () -> {
                        clearingInProgress[0] = true;
                        Utils.runOnBackgroundThread(() -> {
                            String freed = null;
                            CacheStats updated = null;
                            try {
                                freed = megabytes(LyricsCache.clear(bucket));
                                updated = LyricsCache.stats();
                            } finally {
                                final String finalFreed = freed;
                                final CacheStats finalUpdated = updated;
                                Utils.runOnMainThread(() -> {
                                    clearingInProgress[0] = false;
                                    if (finalFreed != null) {
                                        if (bucket == CacheBucket.OFFSET) {
                                            Utils.showToastShort(str("morphe_music_lyrics_cache_offsets_cleared"));
                                        } else {
                                            Utils.showToastShort(str("morphe_music_lyrics_clear_cache_done", finalFreed));
                                        }
                                    }
                                    if (bucket == CacheBucket.OFFSET || bucket == CacheBucket.ALL) {
                                        // Otherwise the panel writes the old value back on close.
                                        LyricsManager.getInstance().clearTemporaryOffsetMs();
                                    }
                                    if (finalUpdated != null) {
                                        onCleared.accept(finalUpdated);
                                    }
                                });
                            }
                        });
                    },
                    () -> { },
                    null,
                    null,
                    true).first;
            confirmDialog.setOnDismissListener(d -> confirmOpen[0] = false);
            confirmDialog.show();
        } catch (WindowManager.BadTokenException ex) {
            confirmOpen[0] = false;
            Logger.printException(() -> "Could not show clear confirmation dialog", ex);
        } catch (RuntimeException ex) {
            confirmOpen[0] = false;
            throw ex;
        }
    }

    private static CardViews addBucketCard(Context context, LinearLayout parent,
                                           String title, boolean withClearButton) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Dim.dp16, Dim.dp12, Dim.dp12, Dim.dp12);
        card.setBackground(roundedSurface(ThemeUtils.getEditTextBackground(), Dim.dp(14),
                withAlpha(ThemeUtils.getDialogTextColor(), 0x1A)));
        card.setClipToOutline(true);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = Dim.dp(10);
        parent.addView(card, cardParams);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textParams.setMarginEnd(Dim.dp12);
        card.addView(textCol, textParams);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(ThemeUtils.getDialogTitleColor());
        textCol.addView(titleView);

        TextView summaryView = new TextView(context);
        summaryView.setTextSize(13);
        summaryView.setTextColor(withAlpha(ThemeUtils.getDialogTextColor(), 0xBF));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = Dim.dp(2);
        textCol.addView(summaryView, summaryParams);

        Button clearBtn = null;
        if (withClearButton) {
            clearBtn = CustomDialog.createButton(
                    context, null, str("morphe_music_lyrics_cache_clear_action"),
                    null, false, false);
            clearBtn.setTextSize(13);
            clearBtn.setTypeface(Typeface.DEFAULT_BOLD);
            clearBtn.setPadding(Dim.dp16, Dim.dp(6), Dim.dp16, Dim.dp(6));
            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Dim.dp(34));
            card.addView(clearBtn, btnParams);
        }

        return new CardViews(summaryView, clearBtn);
    }

    private static CardViews addTotalCard(Context context, LinearLayout parent, String title) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Dim.dp16, Dim.dp12, Dim.dp16, Dim.dp12);
        card.setBackground(roundedSurface(
                withAlpha(ThemeUtils.getEditTextBackground(), 0xB0), Dim.dp(14),
                withAlpha(ThemeUtils.getDialogTextColor(), 0x26)));
        card.setClipToOutline(true);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = Dim.dp(2);
        parent.addView(card, cardParams);

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(ThemeUtils.getDialogTitleColor());
        card.addView(titleView);

        TextView summaryView = new TextView(context);
        summaryView.setTextSize(14);
        summaryView.setTextColor(ThemeUtils.getDialogTextColor());
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = Dim.dp(2);
        card.addView(summaryView, summaryParams);

        return new CardViews(summaryView, null);
    }

    private static void bindCard(CardViews card, CacheBucket bucket, BucketStats stats,
                                 Runnable onClearClick) {
        final String key = switch (bucket) {
            case LYRICS -> "morphe_music_lyrics_cache_lyrics_summary";
            case TRANSLATION -> "morphe_music_lyrics_cache_translation_summary";
            case TRANSLITERATION -> "morphe_music_lyrics_cache_transliteration_summary";
            case AI_SECTION -> "morphe_music_lyrics_cache_ai_section_summary";
            case OFFSET -> "morphe_music_lyrics_cache_offset_summary";
            case ALL -> "morphe_music_lyrics_cache_total_summary";
        };
        card.summaryView.setText(str(key, megabytes(stats.bytes()), stats.items()));
        if (card.clearButton != null) {
            boolean enabled = stats.bytes() > 0 || stats.items() > 0;
            card.clearButton.setEnabled(enabled);
            card.clearButton.setAlpha(enabled ? 1f : 0.4f);
            card.clearButton.setOnClickListener(v -> onClearClick.run());
        }
    }

    private static GradientDrawable roundedSurface(int fillColor, int radiusPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        d.setStroke(Dim.dp(1), strokeColor);
        return d;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** A byte count formatted to two decimal megabytes. */
    private static String megabytes(long bytes) {
        return String.format(Locale.getDefault(), "%.2f", bytes / (1024.0 * 1024.0));
    }

    private record CardViews(TextView summaryView, @androidx.annotation.Nullable Button clearButton) {
    }
}
