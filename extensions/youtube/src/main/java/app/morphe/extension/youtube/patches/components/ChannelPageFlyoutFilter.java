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
        final StringFilterGroup pageHeader = new StringFilterGroup(
                null,
                "page_header.e"
        );

        addPathCallbacks(pageHeader);
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

        final int index = FlyoutUtils.byteIndexOf(buffer, CHANNEL_ID_PREFIX_BYTES);
        if (index < 0) {
            return false;
        }

        if (isValidChannelId(buffer, index)) {
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

    /**
     * Validates if the buffer contains a valid YouTube channel ID at the given index.
     * YouTube channel IDs are always 24 characters long, starting with the prefix "UC".
     * The remaining 22 characters are Base64 URL-safe: A-Z, a-z, 0-9, hyphen (-), and underscore (_).
     *
     * @param buffer The buffer to check.
     * @param index  The start index of the "UC" prefix.
     * @return True if it is a valid channel ID.
     */
    private static boolean isValidChannelId(byte[] buffer, int index) {
        final int lastIndex = index + CHANNEL_ID_LENGTH;
        if (index < 0 || lastIndex > buffer.length) {
            return false;
        }

        if (buffer[index] != 'U' || buffer[index + 1] != 'C') {
            return false;
        }

        for (int i = index + 2; i < lastIndex; i++) {
            final byte b = buffer[i];
            final boolean isValid = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') ||
                    (b >= '0' && b <= '9') || b == '-' || b == '_';
            if (!isValid) {
                return false;
            }
        }
        return true;
    }
}
