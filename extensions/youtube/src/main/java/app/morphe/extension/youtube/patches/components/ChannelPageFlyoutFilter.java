/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

@SuppressWarnings("unused")
public final class ChannelPageFlyoutFilter extends Filter {

    private boolean delayedFetch = false;
    private final byte[] CHANNEL_ID_PREFIX_BYTES =
            "UC".getBytes(StandardCharsets.US_ASCII);
    private static String flyoutChannelId = "";

    public static String getFlyoutChannelId() {
        return flyoutChannelId;
    }

    public ChannelPageFlyoutFilter() {
        addPathCallbacks(new StringFilterGroup(
                null,
                "page_header.e"
        ));
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (!delayedFetch) {
            final int index = indexOf(buffer, CHANNEL_ID_PREFIX_BYTES);

            if (index >= 0) {
                final int youTubeChannelIdLength = 24;
                final int flyoutChannelIdEnd = index + youTubeChannelIdLength;

                if (flyoutChannelIdEnd <= buffer.length) {
                    flyoutChannelId = new String(
                            buffer,
                            index,
                            youTubeChannelIdLength,
                            StandardCharsets.US_ASCII
                    );
                    Logger.printDebug(() -> "Found channelId: " + flyoutChannelId);
                    delayedFetch = true;
                    Utils.runOnMainThreadDelayed(() -> delayedFetch = false, 1000);
                }
            }
        }
        return false;
    }

    @SuppressWarnings("SameParameterValue")
    private static int indexOf(byte[] haystack, byte[] needle) {
        final int needleLength = needle.length;
        for (int i = 0, lastIndex = haystack.length - needleLength; i <= lastIndex; i++) {
            boolean found = true;
            for (int j = 0; j < needleLength; j++) {
                if (haystack[i + j] != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }
}
