/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.spans;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

public class StringSpanFilterGroup extends SpanFilterGroup<CharSequence> {

    public StringSpanFilterGroup(final BooleanSetting setting, final String... filters) {
        super(setting, filters);
    }

    @Override
    public SpanFilterGroup.FilterGroupResult check(CharSequence string) {
        int matchedIndex = -1;
        if (isEnabled()) {
            for (CharSequence pattern : filters) {
                //noinspection SizeReplaceableByIsEmpty
                if (string.length() > 0) {
                    final int indexOf = Utils.indexOf(string, pattern);
                    if (indexOf >= 0) {
                        matchedIndex = indexOf;
                        break;
                    }
                }
            }
        }
        return new SpanFilterGroup.FilterGroupResult(setting, matchedIndex);
    }
}
