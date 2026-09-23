/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.components;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

public class StringFilterGroup extends FilterGroup<String> {

    public StringFilterGroup(final BooleanSetting setting, final String... filters) {
        super(setting, filters);
    }

    @Override
    public FilterGroupResult check(final String string) {
        return check((CharSequence) string);
    }

    public FilterGroupResult check(final CharSequence text) {
        int matchedIndex = -1;
        int matchedLength = 0;
        //noinspection SizeReplaceableByIsEmpty
        if (isEnabled() && text.length() != 0) {
            for (String pattern : filters) {
                final int indexOf = Utils.indexOf(text, pattern);
                if (indexOf >= 0) {
                    matchedIndex = indexOf;
                    matchedLength = pattern.length();
                    break;
                }
            }
        }
        return new FilterGroupResult(setting, matchedIndex, matchedLength);
    }
}
