/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.spans;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Filters litho based components.
 * <p>
 * All callbacks must be registered before the constructor completes.
 */
public abstract class SpanFilter {
    private static final RelativeSizeSpan relativeSizeSpanDummy = new RelativeSizeSpan(0f);
    private static final Drawable transparentDrawable = new ColorDrawable(Color.TRANSPARENT);
    private static final ImageSpan imageSpanDummy = new ImageSpan(transparentDrawable);

    /**
     * Path callbacks. Do not add to this instance,
     * and instead use {@link #addCallbacks(StringSpanFilterGroup...)}.
     */
    public final List<StringSpanFilterGroup> callbacks = new ArrayList<>();

    /**
     * Adds callbacks to {@link #skip(String, SpannableString, Object, int, int, int, boolean, SpanType, StringSpanFilterGroup)}
     * if any of the groups are found.
     */
    protected final void addCallbacks(StringSpanFilterGroup... groups) {
        callbacks.addAll(Arrays.asList(groups));
    }

    protected final void hideSpan(SpannableString spannableString, int start, int end, int flags) {
        spannableString.setSpan(relativeSizeSpanDummy, start, end, flags);
    }

    protected final void hideImageSpan(SpannableString spannableString, int start, int end, int flags) {
        spannableString.setSpan(imageSpanDummy, start, end, flags);
    }

    /**
     * Called after an enabled filter has been matched.
     * Default implementation is to always filter the matched component and log the action.
     * Subclasses can perform additional or different checks if needed.
     * <p>
     * Method is called off the main thread.
     *
     * @param matchedGroup The actual filter that matched.
     */
    public boolean skip(String conversionContext, SpannableString spannableString, Object span, int start, int end,
                 int flags, boolean isWord, SpanType spanType, StringSpanFilterGroup matchedGroup) {
        return true;
    }
}
