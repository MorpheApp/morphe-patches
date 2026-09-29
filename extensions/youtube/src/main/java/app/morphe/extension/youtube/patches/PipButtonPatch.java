/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3397
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.util.Rational;
import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class PipButtonPatch {

    private static final Rational DEFAULT_ASPECT_RATIO = new Rational(16, 9);

    /**
     * Picture-in-picture rejects aspect ratios wider than 2.39:1 or taller than 1:2.39.
     */
    private static final float MAX_ASPECT_RATIO = 2.39f;
    private static final float MIN_ASPECT_RATIO = 1 / MAX_ASPECT_RATIO;

    public static boolean isPipSupported() {
        return Utils.getContext().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    public static void enterPictureInPicture() {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null) {
                Logger.printDebug(() -> "Activity is null, cannot enter Picture-in-picture");
                return;
            }

            activity.enterPictureInPictureMode(buildPipParams(activity));
        } catch (Exception ex) {
            Logger.printException(() -> "enterPictureInPicture failure", ex);
        }
    }

    private static PictureInPictureParams buildPipParams(Activity activity) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        Rational aspectRatio = DEFAULT_ASPECT_RATIO;

        View playerView = activity.findViewById(ResourceUtils.getIdIdentifier("player_view"));
        if (playerView != null && playerView.getWidth() > 0 && playerView.getHeight() > 0) {
            final int width = playerView.getWidth();
            final int height = playerView.getHeight();
            final float ratio = (float) width / height;
            if (ratio >= MIN_ASPECT_RATIO && ratio <= MAX_ASPECT_RATIO) {
                aspectRatio = new Rational(width, height);
            }

            Rect sourceRect = new Rect();
            if (playerView.getGlobalVisibleRect(sourceRect)) {
                builder.setSourceRectHint(sourceRect);
            }
        }

        return builder.setAspectRatio(aspectRatio).build();
    }
}
