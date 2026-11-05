/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4376
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps bilingual native windows in language order, retaining each effect layer. */
final class NativeCaptionPlacement {
    private record Scale(float x, float y) {}
    private static final Map<View, Scale> compacted = new WeakHashMap<>();

    record Bounds(int left, int top, int right, int bottom) {
        boolean overlaps(Bounds other) {
            return left < other.right && right > other.left && top < other.bottom && bottom > other.top;
        }
        Bounds shifted(int dy) { return new Bounds(left, top + dy, right, bottom + dy); }
        Bounds scaled(float factor) {
            int width = Math.round((right - left) * factor), height = Math.round((bottom - top) * factor);
            int x = left + (right - left - width) / 2, y = top + (bottom - top - height) / 2;
            return new Bounds(x, y, x + width, y + height);
        }
    }

    record Placement(int[] offsets, float scale, int[] primaryOffsets) {}

    static void afterLayout(ViewGroup parent) {
        List<Bounds> primary = new ArrayList<>(), secondary = new ArrayList<>();
        List<View> firstViews = new ArrayList<>(), views = new ArrayList<>();
        View translation = null;
        int primaryTranslation = -1, secondaryTranslation = -1;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            Scale original = compacted.remove(child);
            if (original != null) {
                child.setScaleX(original.x);
                child.setScaleY(original.y);
            }
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view.getVisibility() != View.VISIBLE
                    || !(view instanceof StableCaptionBlocks.Window window)
                    || !(window.patch_getCaptionCue() instanceof StableCaptionBlocks.Cue cue)) continue;
            StableCaptionBlocks.Frame frame = cue.patch_getBlockFrame();
            if (frame == null || frame.state.nativePair == null
                    || !frame.nativeStyle && !DualCaptionOverlay.hasText(view)) continue;
            Bounds bounds = new Bounds(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
            if (!frame.nativeStyle) translation = view;
            if (frame.secondaryNativeCaption()) {
                if (!frame.nativeStyle) secondaryTranslation = secondary.size();
                secondary.add(bounds);
                views.add(view);
            } else {
                if (!frame.nativeStyle) primaryTranslation = primary.size();
                primary.add(bounds);
                firstViews.add(view);
            }
        }
        Placement placement = fit(primary, secondary, primaryTranslation, secondaryTranslation,
                parent.getPaddingTop(), parent.getHeight() - parent.getPaddingBottom(),
                Math.max(1, Math.round(2 * parent.getResources().getDisplayMetrics().density)));
        if (translation != null) {
            if (placement.scale < 1) {
                Scale original = new Scale(translation.getScaleX(), translation.getScaleY());
                compacted.put(translation, original);
                translation.setScaleX(original.x * placement.scale);
                translation.setScaleY(original.y * placement.scale);
            }
        }
        for (int i = 0; i < firstViews.size(); i++) firstViews.get(i).offsetTopAndBottom(placement.primaryOffsets[i]);
        for (int i = 0; i < views.size(); i++) views.get(i).offsetTopAndBottom(placement.offsets[i]);
    }

    /** Keep the first language above the second; only unstyled translation may shrink. */
    static Placement fit(List<Bounds> primary, List<Bounds> secondary,
                         int primaryTranslation, int secondaryTranslation, int top, int bottom, int gap) {
        Placement placed = ordered(primary, secondary, top, bottom, gap);
        if (placed != null) return placed;
        if (primaryTranslation >= 0 || secondaryTranslation >= 0) {
            Bounds translation = primaryTranslation >= 0 ? primary.get(primaryTranslation) : secondary.get(secondaryTranslation);
            for (int percent = 95; percent >= 40; percent -= 5) {
                float scale = percent / 100f;
                List<Bounds> first = new ArrayList<>(primary), second = new ArrayList<>(secondary);
                if (primaryTranslation >= 0) first.set(primaryTranslation, translation.scaled(scale));
                else second.set(secondaryTranslation, translation.scaled(scale));
                placed = ordered(first, second, top, bottom, gap);
                if (placed != null) return new Placement(placed.offsets, scale, placed.primaryOffsets);
            }
        }
        // Arbitrarily dense authored windows may not fit without changing their font size.
        return new Placement(new int[secondary.size()], 1, new int[primary.size()]);
    }

    private record Relation(int before, int after, int distance) {}

    private static Placement ordered(List<Bounds> primary, List<Bounds> secondary, int top, int bottom, int gap) {
        List<Bounds> windows = new ArrayList<>(primary);
        windows.addAll(secondary);
        List<List<Integer>> groups = groups(primary);
        for (List<Integer> group : groups(secondary)) {
            List<Integer> indices = new ArrayList<>();
            for (int i : group) indices.add(i + primary.size());
            groups.add(indices);
        }
        int count = groups.size();
        int[] ids = new int[windows.size()], lower = new int[count], upper = new int[count];
        for (int g = 0; g < count; g++) {
            lower[g] = Integer.MIN_VALUE;
            upper[g] = Integer.MAX_VALUE;
            for (int i : groups.get(g)) {
                ids[i] = g;
                lower[g] = Math.max(lower[g], top - windows.get(i).top);
                upper[g] = Math.min(upper[g], bottom - windows.get(i).bottom);
            }
            if (lower[g] > upper[g]) return null;
        }
        List<Relation> relations = new ArrayList<>();
        for (int i = 0; i < windows.size(); i++) for (int j = i + 1; j < windows.size(); j++) {
            Bounds a = windows.get(i), b = windows.get(j);
            if (ids[i] == ids[j] || a.left >= b.right || a.right <= b.left) continue;
            if (i < primary.size() && j >= primary.size()) {
                relations.add(new Relation(ids[i], ids[j], a.bottom + gap - b.top));
            } else if (a.bottom <= b.top) {
                relations.add(new Relation(ids[i], ids[j], a.bottom - b.top));
            } else if (b.bottom <= a.top) {
                relations.add(new Relation(ids[j], ids[i], b.bottom - a.top));
            }
        }
        // Reserve room from the bottom upwards, including other windows of the same language.
        for (int pass = 0; pass < count; pass++) {
            boolean changed = false;
            for (Relation r : relations) {
                int limit = upper[r.after] - r.distance;
                if (upper[r.before] <= limit) continue;
                upper[r.before] = limit;
                if (limit < lower[r.before]) return null;
                changed = true;
            }
            if (!changed) break;
            if (pass == count - 1) return null;
        }
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) offsets[i] = Math.max(lower[i], Math.min(0, upper[i]));
        // Keep authored positions where possible, moving later windows only as far as required.
        for (int pass = 0; pass < count; pass++) {
            boolean changed = false;
            for (Relation r : relations) {
                int position = offsets[r.before] + r.distance;
                if (offsets[r.after] >= position) continue;
                if (position > upper[r.after]) return null;
                offsets[r.after] = position;
                changed = true;
            }
            if (!changed) break;
            if (pass == count - 1) return null;
        }
        int[] first = new int[primary.size()], second = new int[secondary.size()];
        for (int i = 0; i < first.length; i++) first[i] = offsets[ids[i]];
        for (int i = 0; i < second.length; i++) second[i] = offsets[ids[i + first.length]];
        return new Placement(second, 1, first);
    }

    private static List<List<Integer>> groups(List<Bounds> windows) {
        List<List<Integer>> result = new ArrayList<>();
        boolean[] visited = new boolean[windows.size()];
        for (int start = 0; start < windows.size(); start++) {
            if (visited[start]) continue;
            List<Integer> group = new ArrayList<>();
            result.add(group);
            group.add(start);
            visited[start] = true;
            for (int index = 0; index < group.size(); index++) {
                Bounds member = windows.get(group.get(index));
                for (int next = 0; next < windows.size(); next++) {
                    if (!visited[next] && member.overlaps(windows.get(next))) {
                        visited[next] = true;
                        group.add(next);
                    }
                }
            }
        }
        return result;
    }
}
