/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.notifications;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The videos of the notifications of one notifications response, and which of them are seen.
 */
final class InboxVideos {

    private final List<String> videoIds = new ArrayList<>();
    private final List<String> seenVideoIds = new ArrayList<>();
    private final List<String> unseenVideoIds = new ArrayList<>();

    void add(@Nullable String videoId, boolean unseen) {
        if (videoId == null) {
            return;
        }
        videoIds.add(videoId);
        (unseen ? unseenVideoIds : seenVideoIds).add(videoId);
    }

    void saveInBackground() {
        SeenVideos.updateInBackground(videoIds, seenVideoIds, unseenVideoIds);
    }
}
