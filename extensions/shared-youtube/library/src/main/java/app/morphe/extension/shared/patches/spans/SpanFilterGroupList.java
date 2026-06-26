/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.patches.spans;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.function.Consumer;

import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.TrieSearch;

public abstract class SpanFilterGroupList<V, T extends SpanFilterGroup<V>> implements Iterable<T> {

    private final List<T> filterGroups = new ArrayList<>();
    private final TrieSearch<V> search = createSearchGraph();

    @SafeVarargs
    public final void addAll(final T... groups) {
        filterGroups.addAll(Arrays.asList(groups));

        for (T group : groups) {
            if (!group.includeInSearch()) {
                continue;
            }
            for (V pattern : group.filters) {
                search.addPattern(pattern, (textSearched, matchedStartIndex,
                                            matchedLength, callbackParameter) -> {
                    if (group.isEnabled()) {
                        SpanFilterGroup.FilterGroupResult result =
                                (SpanFilterGroup.FilterGroupResult) callbackParameter;
                        result.setValues(group.setting, matchedStartIndex);
                        return true;
                    }
                    return false;
                });
            }
        }
    }

    @NonNull
    @Override
    public Iterator<T> iterator() {
        return filterGroups.iterator();
    }

    @Override
    public void forEach(@NonNull Consumer<? super T> action) {
        filterGroups.forEach(action);
    }

    @NonNull
    @Override
    public Spliterator<T> spliterator() {
        return filterGroups.spliterator();
    }

    public SpanFilterGroup.FilterGroupResult check(V stack) {
        SpanFilterGroup.FilterGroupResult result = new SpanFilterGroup.FilterGroupResult();
        search.matches(stack, result);
        return result;
    }

    protected abstract TrieSearch<V> createSearchGraph();
}
