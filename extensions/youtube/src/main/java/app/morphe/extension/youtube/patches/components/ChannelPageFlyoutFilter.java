/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.youtube.patches.utils.FlyoutUtils.CHANNEL_ID_LENGTH;
import static app.morphe.extension.youtube.patches.utils.FlyoutUtils.getAsciiBytes;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;

@SuppressWarnings("unused")
public final class ChannelPageFlyoutFilter extends Filter {

    private static final byte[] CHANNEL_ID_PREFIX_BYTES = getAsciiBytes("UC");
    private static String flyoutChannelId = "";
    private volatile boolean delayedFetch;

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
        if (delayedFetch) {
            return false;
        }

        final int index = FlyoutUtils.indexOf(buffer, CHANNEL_ID_PREFIX_BYTES);
        if (index < 0) {
            return false;
        }

        if (FlyoutUtils.isValidChannelId(buffer, index)) {
            flyoutChannelId = new String(
                    buffer,
                    index,
                    CHANNEL_ID_LENGTH,
                    StandardCharsets.US_ASCII
            );
            Logger.printDebug(() -> "Found channelId: " + flyoutChannelId);
            delayedFetch = true;
            Utils.runOnMainThreadDelayed(() -> delayedFetch = false, 1000);
        }
        return false;
    }
}
