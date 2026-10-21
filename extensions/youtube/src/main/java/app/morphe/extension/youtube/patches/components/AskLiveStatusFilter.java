/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.ask.LiveStatusRequest;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Finds the videos of lists that are live or upcoming as the lists show them,
 * so the menu of such a video leaves out Summarize without waiting for a request.
 * The text of the list items is in the language of the app, so only icons and commands are used.
 */
@SuppressWarnings("unused")
public final class AskLiveStatusFilter extends Filter {

    /**
     * Icons of the 'LIVE' thumbnail badge. Past streams have no badge.
     */
    private final ByteArrayFilterGroup liveBadgeIcons = new ByteArrayFilterGroup(
            null,
            "yt_outline_live_black",
            "yt_outline_experimental_live_black"
    );

    /**
     * Nested components of a list item can include a shared list of many icon names,
     * which has the 'LIVE' badge icon too. The thumbnail with the badge has only a few icon names.
     */
    private static final Pattern ICON_NAME = Pattern.compile("yt_(?:outline|fill)_\\w+?_\\d{2}");
    private static final int MAX_THUMBNAIL_ICONS = 2;

    private static final Pattern THUMBNAIL_VIDEO_ID = Pattern.compile("/vi(?:_\\w+)?/([\\w-]{11})/");

    /**
     * The parameters of the 'Notify me' command of the menu of an upcoming video are
     * {1: video id, 2: number, 4: number}, which is 23 characters of base64 that start with 'Cgs' to 'Cgv'.
     */
    private static final Pattern REMINDER_PARAMETERS = Pattern.compile("(?<![\\w-])Cg[s-v][\\w-]{20}(?![\\w-])");
    private static final int REMINDER_PARAMETERS_LENGTH = 17;
    private static final int VIDEO_ID_LENGTH = 11;

    public AskLiveStatusFilter() {
        addPathCallbacks(
                new StringFilterGroup(
                        Settings.ASK_SUMMARIZE_FLYOUT_MENU,
                        // The thumbnail has the live badge, and the menu button has the menu.
                        "video_lockup_with_attachment.e",
                        "thumbnail.e",
                        "overflow_button.e"
                )
        );
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              CharSequence path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType, int contentIndex) {
        try {
            if (liveBadgeIcons.check(buffer).isFiltered() && countIconNames(asciiStrings.getStrings()) <= MAX_THUMBNAIL_ICONS) {
                Matcher videoId = THUMBNAIL_VIDEO_ID.matcher(asciiStrings.getStrings());
                if (videoId.find()) {
                    final String id = videoId.group(1);
                    Logger.printDebug(() -> "Live video in the list: " + id + " path: " + path);
                    LiveStatusRequest.setLiveOrUpcoming(id);
                }
            } else {
                String upcomingVideoId = findReminderVideoId(asciiStrings.getStrings());
                if (upcomingVideoId != null) {
                    Logger.printDebug(() -> "Upcoming video in the list: " + upcomingVideoId + " path: " + path);
                    LiveStatusRequest.setLiveOrUpcoming(upcomingVideoId);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "isFiltered failure", ex);
        }

        // The filter only reads the list items.
        return false;
    }

    /**
     * @return The number of icon names, up to one more than {@link #MAX_THUMBNAIL_ICONS}.
     */
    private static int countIconNames(String strings) {
        Matcher icon = ICON_NAME.matcher(strings);
        int count = 0;
        while (count <= MAX_THUMBNAIL_ICONS && icon.find()) {
            count++;
        }
        return count;
    }

    @Nullable
    private static String findReminderVideoId(String strings) {
        Matcher parameters = REMINDER_PARAMETERS.matcher(strings);
        while (parameters.find()) {
            // The pattern only matches valid base64.
            byte[] decoded = Base64.decode(parameters.group(),
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            // 0x0A 0x0B <video id> 0x10 <number> 0x20 <number>
            if (decoded.length == REMINDER_PARAMETERS_LENGTH
                    && decoded[0] == 0x0A && decoded[1] == VIDEO_ID_LENGTH
                    && decoded[13] == 0x10 && decoded[15] == 0x20) {
                return new String(decoded, 2, VIDEO_ID_LENGTH, StandardCharsets.US_ASCII);
            }
        }
        return null;
    }
}
