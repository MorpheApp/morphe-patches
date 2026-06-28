/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.spans;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.settings.BooleanSetting;

public abstract class SpanFilterGroup<T> {
    public final static class FilterGroupResult {
        private BooleanSetting setting;
        private int matchedIndex;

        public FilterGroupResult() {
            this(null, -1);
        }

        public FilterGroupResult(BooleanSetting setting, int matchedIndex) {
            setValues(setting, matchedIndex);
        }

        public void setValues(BooleanSetting setting, int matchedIndex) {
            this.setting = setting;
            this.matchedIndex = matchedIndex;
        }

        public BooleanSetting getSetting() {
            return setting;
        }

        public boolean isFiltered() {
            return matchedIndex >= 0;
        }
    }

    public final BooleanSetting setting;
    public final T[] filters;

    @SafeVarargs
    public SpanFilterGroup(final BooleanSetting setting, final T... filters) {
        this.setting = setting;
        this.filters = filters;
        if (filters.length == 0) {
            throw new IllegalArgumentException("Must use one or more filter patterns (zero specified)");
        }
    }

    public boolean isEnabled() {
        return setting == null || setting.get();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean includeInSearch() {
        return isEnabled() || !setting.rebootApp;
    }

    @NonNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + (setting == null ? "(null setting)" : setting);
    }

    public abstract FilterGroupResult check(final T stack);
}
