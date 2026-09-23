package app.morphe.extension.shared.patches.components;

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
        if (isEnabled() && text.length() != 0) {
            for (String pattern : filters) {
                final int indexOf = CharSequenceSearch.indexOf(text, pattern);
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
