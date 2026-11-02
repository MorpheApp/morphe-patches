/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;

import org.junit.Test;

import java.util.List;
import java.util.EnumMap;
import java.io.ByteArrayOutputStream;

import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.*;
import app.morphe.extension.youtube.innertube.IconOuterClass.Icon;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;

public class WatchLaterNavigationTest {
    @Test
    public void selectedClockIsRegisteredWithoutChangingOtherTabs() {
        EnumMap<YTIconType, Integer> icons = new EnumMap<>(YTIconType.class);
        icons.put(YTIconType.PIVOT_HOME, 10);
        WatchLaterNavigation.setSelectedIcon(icons, YTIconType.WATCH_LATER, 20);
        assertEquals(Integer.valueOf(20), icons.get(YTIconType.WATCH_LATER));
        assertEquals(Integer.valueOf(10), icons.get(YTIconType.PIVOT_HOME));
        assertEquals(2, icons.size());
    }

    @Test
    public void selectedClockReplacesOutlineFallbackAndIgnoresMissingResource() {
        EnumMap<YTIconType, Integer> icons = new EnumMap<>(YTIconType.class);
        icons.put(YTIconType.WATCH_LATER, 10);
        WatchLaterNavigation.setSelectedIcon(icons, YTIconType.WATCH_LATER, 20);
        assertEquals(Integer.valueOf(20), icons.get(YTIconType.WATCH_LATER));
        WatchLaterNavigation.setSelectedIcon(icons, YTIconType.WATCH_LATER, 0);
        assertEquals(Integer.valueOf(20), icons.get(YTIconType.WATCH_LATER));
    }

    private byte[] home(YTIconType icon) throws Exception {
        byte[] bytes = PivotBarItemRenderer.newBuilder()
                .setPivotIdentifier("FEwhat_to_watch")
                .setTargetId("home-target")
                .setNavigationEndpoint(NavigationEndpoint.newBuilder()
                        .setBrowseEndpoint(BrowseEndpoint.newBuilder().setBrowseId("FEwhat_to_watch")))
                .setIcon(Icon.newBuilder().setYtIconType(icon))
                .setPresentationStyle(PresentationStyle.newBuilder().setPivotBarItemStyle(2))
                .build().toByteArray();
        return PivotBarItemRendererBytes.parseFrom(bytes).toBuilder()
                .setTrackingParams(ByteString.copyFromUtf8("home-tracking"))
                .setThumbnail(ByteString.copyFromUtf8("home-image"))
                .setNavigationType(2).build().toByteArray();
    }

    @Test
    public void transformsBothHomeIconsWithoutChangingSource() throws Exception {
        for (YTIconType icon : List.of(YTIconType.PIVOT_HOME, YTIconType.TAB_HOME_CAIRO)) {
            byte[] source = home(icon);
            byte[] result = WatchLaterNavigation.transform(source, "Später ansehen");
            PivotBarItemRenderer tab = PivotBarItemRenderer.parseFrom(result);
            assertEquals(WatchLaterNavigation.IDENTIFIER, tab.getPivotIdentifier());
            assertEquals("VLWL", tab.getNavigationEndpoint().getBrowseEndpoint().getBrowseId());
            assertEquals("Später ansehen", tab.getAccessibility().getAccessibilityData().getLabel());
            assertEquals("Später ansehen", tab.getTitle().getRuns().getText());
            assertEquals(YTIconType.WATCH_LATER, tab.getIcon().getYtIconType());
            assertEquals(2, tab.getPresentationStyle().getPivotBarItemStyle());
            PivotBarItemRendererBytes wire = PivotBarItemRendererBytes.parseFrom(result);
            assertTrue(wire.getTrackingParams().isEmpty());
            assertTrue(wire.getTargetId().isEmpty());
            assertTrue(wire.getThumbnail().isEmpty());
            assertEquals(1, wire.getNavigationType());
            assertEquals("FEwhat_to_watch", PivotBarItemRenderer.parseFrom(source).getPivotIdentifier());
            assertNull(WatchLaterNavigation.transform(result, "Again"));
        }
    }

    @Test
    public void ignoresNonHomeAndEmptyMessages() throws Exception {
        assertNull(WatchLaterNavigation.transform(home(YTIconType.SETTINGS_CAIRO), "Watch Later"));
        assertNull(WatchLaterNavigation.transform(new byte[0], "Watch Later"));
    }

    @Test(expected = com.google.protobuf.InvalidProtocolBufferException.class)
    public void rejectsMalformedRenderer() throws Exception {
        WatchLaterNavigation.transform(new byte[] {10, 127}, "Watch Later");
    }

    @Test
    public void preservesUnknownRendererFieldsButReplacesTheEntireHomeEndpoint() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(home(YTIconType.PIVOT_HOME));
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(99, "future-renderer-field");
        output.flush();
        byte[] result = WatchLaterNavigation.transform(bytes.toByteArray(), "Watch Later");
        CodedInputStream input = CodedInputStream.newInstance(result);
        boolean found = false;
        for (int tag; (tag = input.readTag()) != 0;) {
            if (tag == (99 << 3 | 2)) {
                assertEquals("future-renderer-field", input.readString());
                found = true;
            } else {
                input.skipField(tag);
            }
        }
        assertTrue(found);
        assertArrayEquals(NavigationEndpoint.newBuilder()
                        .setBrowseEndpoint(BrowseEndpoint.newBuilder().setBrowseId("VLWL"))
                        .build().toByteArray(),
                PivotBarItemRenderer.parseFrom(result).getNavigationEndpoint().toByteArray());
    }

    @Test
    public void insertionClampsAndDoesNotMutateInput() throws Exception {
        List<String> source = List.of("home", "search", "settings", "you");
        assertEquals(List.of("later", "home", "search", "settings", "you"),
                WatchLaterNavigation.insert(source, "later", -10));
        assertEquals(List.of("home", "search", "settings", "you", "later"),
                WatchLaterNavigation.insert(source, "later", 99));
        assertEquals(List.of("home", "search", "later", "settings", "you"),
                WatchLaterNavigation.insert(source, "later", 2));
        assertEquals(4, source.size());
        assertSame(source, WatchLaterNavigation.insert(source, null, 0));
        assertNull(WatchLaterNavigation.insert(null, "later", 0));
        assertTrue(WatchLaterNavigation.insert(List.of(), "later", 0).isEmpty());
    }

    @Test
    public void insertionIsIdempotentIncludingRebuiltWrappers() throws Exception {
        Object renderer = new Object();
        List<Object> once = WatchLaterNavigation.insert(List.of("home"), renderer, 1);
        assertSame(once, WatchLaterNavigation.insert(once, renderer, 1));
        MessageLite tab = PivotBarItemRenderer.parseFrom(
                WatchLaterNavigation.transform(home(YTIconType.PIVOT_HOME), "Watch Later"));
        WatchLaterNavigation.Renderer rebuilt = () -> tab;
        List<Object> existing = List.of("home", rebuilt);
        assertSame(existing, WatchLaterNavigation.insert(existing, renderer, 1));
    }
}
