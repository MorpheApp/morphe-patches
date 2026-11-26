/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.components;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

public class StringFilterGroup extends FilterGroup<CharSequence> {

    public StringFilterGroup(BooleanSetting setting, String... filters) {
        super(setting, filters);
    }

    @Override
    public FilterGroupResult check(CharSequence text) {
        int matchedIndex = -1;
        int matchedLength = 0;
        if (isEnabled() && Utils.isNotEmpty(text)) {
            for (CharSequence pattern : filters) {
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
