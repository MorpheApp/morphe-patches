/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.spans;

public enum SpanType {
    ABSOLUTE_SIZE("AbsoluteSizeSpan"),
    CLICKABLE("ClickableSpan"),
    CUSTOM_CHARACTER_STYLE("CustomCharacterStyle"),
    FOREGROUND_COLOR("ForegroundColorSpan"),
    IMAGE("ImageSpan"),
    LINE_HEIGHT("LineHeightSpan"),
    TYPEFACE("TypefaceSpan"),
    UNKNOWN("Unknown");

    public final String type;

    SpanType(String type) {
        this.type = type;
    }
}
