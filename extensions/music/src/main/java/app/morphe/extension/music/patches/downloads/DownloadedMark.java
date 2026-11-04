/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4414
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.IconSpan;

/**
 * The mark the songs already downloaded carry before their details in the lists of the app.
 */
public final class DownloadedMark {

    /**
     * Check mark that the icon is drawn over, which still reads as the mark if the icon could
     * not be loaded or the span is dropped.
     */
    private static final String PLACEHOLDER = "✓";
    private static final String SEPARATOR = " ";
    private static final float ICON_SIZE = 0.9f;
    private static final float ICON_OPACITY = 1f;
    @Nullable
    private static final Drawable ICON = ResourceUtils.getDrawable("morphe_music_downloaded_mark");

    private DownloadedMark() {
    }

    /**
     * @return If a track with this video id is downloaded. The downloads folder is listed the
     *         first time, which takes a few milliseconds.
     */
    public static boolean isDownloaded(String videoId) {
        return OfflineCatalogue.contains(videoId);
    }

    /**
     * The mark goes before the text, so a long text cut with an ellipsis keeps it. The spans
     * the text already has move along with it.
     *
     * @return The text with the mark before it, or the text itself if it already has one.
     */
    public static CharSequence addTo(CharSequence text) {
        // A reused text reaches the hook again with the mark it was given when it was created.
        if (text instanceof Spanned spanned
                && spanned.getSpans(0, spanned.length(), IconSpan.class).length > 0) {
            return text;
        }

        SpannableStringBuilder marked = new SpannableStringBuilder(text);
        marked.insert(0, PLACEHOLDER + SEPARATOR);
        if (ICON != null) {
            marked.setSpan(new IconSpan(ICON, ICON_SIZE, ICON_OPACITY),
                    0, PLACEHOLDER.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return marked;
    }
}
