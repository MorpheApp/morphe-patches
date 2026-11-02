/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.protobuf.MessageLite;

import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Accessibility;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.AccessibilityData;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.BrowseEndpoint;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.NavigationType;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRendererBytes;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Runs;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.Title;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;

/** Native tab data only. YouTube keeps ownership of click handling and the tab back stack. */
public final class WatchLaterNavigation {
    static final String IDENTIFIER = "morphe_watch_later";

    public interface Renderer {
        MessageLite patch_getWatchLaterPivotBarItemRenderer();
    }

    /** The native selected-icon map falls back to the outline clock without this entry. */
    static <K> void setSelectedIcon(Map<K, Integer> icons, K watchLater, int drawable) {
        if (drawable != 0) icons.put(watchLater, drawable);
    }

    static byte[] transform(byte[] original, String label) throws Exception {
        PivotBarItemRenderer item = PivotBarItemRenderer.parseFrom(original);
        YTIconType icon = item.getIcon().getYtIconType();
        if (icon != YTIconType.PIVOT_HOME && icon != YTIconType.TAB_HOME_CAIRO) return null;

        byte[] transformed = item.toBuilder()
                .setPivotIdentifier(IDENTIFIER)
                .setNavigationEndpoint(NavigationEndpoint.newBuilder()
                        .setBrowseEndpoint(BrowseEndpoint.newBuilder().setBrowseId("VLWL")))
                .setAccessibility(Accessibility.newBuilder()
                        .setAccessibilityData(AccessibilityData.newBuilder().setLabel(label)))
                .setTitle(Title.newBuilder().setRuns(Runs.newBuilder().setText(label)))
                // The Cairo clock has no drawable on some supported versions, including 21.30.
                .setIcon(Icon.newBuilder().setYtIconType(YTIconType.WATCH_LATER))
                .build().toByteArray();
        // These fields identify/track Home, not the new tab. Preserve other unknown fields.
        return PivotBarItemRendererBytes.parseFrom(transformed).toBuilder()
                .clearTrackingParams().clearTargetId().clearThumbnail()
                .setNavigationType(NavigationType.PIVOT_BAR_NAVIGATION_TYPE_SELECT_TAB_VALUE)
                .build().toByteArray();
    }

    static <T> List<T> insert(List<T> list, T renderer, int index) throws Exception {
        if (list == null || list.isEmpty() || renderer == null || list.contains(renderer)) return list;
        for (T entry : list) {
            if (entry instanceof Renderer access) {
                MessageLite item = access.patch_getWatchLaterPivotBarItemRenderer();
                if (item != null && IDENTIFIER.equals(PivotBarItemRenderer.parseFrom(
                        item.toByteArray()).getPivotIdentifier())) return list;
            }
        }
        List<T> result = new ArrayList<>(list);
        result.add(Math.max(0, Math.min(index, result.size())), renderer);
        return result;
    }

    private WatchLaterNavigation() {}
}
