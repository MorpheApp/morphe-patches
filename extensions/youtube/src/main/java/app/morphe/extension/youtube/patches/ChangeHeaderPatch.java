/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2910
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.util.LinkedList;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ChangeHeaderPatch {

    public enum HeaderLogo {
        DEFAULT(null, null),
        REGULAR("ytWordmarkHeader", "yt_ringo2_wordmark_header"),
        PREMIUM("ytPremiumWordmarkHeader", "yt_ringo2_premium_wordmark_header"),
        MORPHE("morphe_header"),
        CUSTOM("morphe_header_custom");

        @Nullable
        private final String attributeName;
        @Nullable
        private final String drawableName;

        HeaderLogo(String attributeName) {
            this(Objects.requireNonNull(attributeName), Objects.requireNonNull(attributeName));
        }

        HeaderLogo(@Nullable String attributeName, @Nullable String drawableName) {
            this.attributeName = attributeName;
            this.drawableName = drawableName;
        }

        /**
         * @return The attribute id of this header logo, or NULL if the logo should not be replaced.
         */
        @Nullable
        public Integer getAttributeId() {
            if (attributeName == null) {
                return null;
            }

            final int identifier = ResourceUtils.getIdentifier(ResourceType.ATTR, attributeName);
            if (identifier == 0) {
                // Should never happen.
                Logger.printException(() -> "Could not find attribute: " + drawableName);
                Settings.HEADER_LOGO.resetToDefault();
                return null;
            }

            return identifier;
        }

        @Nullable
        public Drawable getDrawable() {
            if (drawableName == null) {
                return null;
            }

            String drawableFullName = drawableName + (Utils.isDarkModeEnabled()
                    ? "_dark"
                    : "_light");

            final int identifier = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, drawableFullName);
            if (identifier != 0) {
                return Utils.getContext().getDrawable(identifier);
            }

            // Should never happen.
            Logger.printException(() -> "Could not find drawable: " + drawableFullName);
            Settings.HEADER_LOGO.resetToDefault();
            return null;
        }
    }

    /**
     * Injection point.
     */
    public static int getHeaderAttributeId(int original) {
        return Objects.requireNonNullElse(Settings.HEADER_LOGO.get().getAttributeId(), original);
    }

    public static Drawable getDrawable(Drawable original) {
        Drawable logo = Settings.HEADER_LOGO.get().getDrawable();
        if (logo != null) {
            return logo;
        }

        // TODO: If 'Hide Doodles' is enabled, this will force the regular logo regardless
        //       what account the user has. This can be improved the next time a Doodle is
        //       active and the attribute id is passed to this method so the correct
        //       regular/premium logo is returned.
        logo = HeaderLogo.REGULAR.getDrawable();
        if (logo != null) {
            return logo;
        }

        // Should never happen.
        Logger.printException(() -> "Could not find regular header logo resource");
        return original;
    }

    private static int drawerContentViewId = -1;

    /**
     * Injection point.
     */
    public static void updateDrawerLogo(ViewGroup drawerContentView) {
        if (drawerContentView == null) {
            return;
        }

        if (drawerContentViewId == -1) {
            drawerContentViewId = ResourceUtils.getIdentifier(ResourceType.ID, "drawer_content_view");
        }

        if (drawerContentViewId != 0 && drawerContentView.getId() != drawerContentViewId) {
            return;
        }

        drawerContentView.post(() -> {
            LinkedList<ViewGroup> queue = new LinkedList<>();
            queue.add(drawerContentView);

            while (!queue.isEmpty()) {
                ViewGroup current = queue.poll();
                for (int i = 0; i < Objects.requireNonNull(current).getChildCount(); i++) {
                    View child = current.getChildAt(i);

                    if (child instanceof ImageView logoView) {

                        if (logoView.getTag() == null) {
                            logoView.setTag(true);

                            logoView.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    Drawable currentDrawable = logoView.getDrawable();

                                    if (currentDrawable != null) {
                                        int viewWidth = logoView.getWidth();
                                        int viewHeight = logoView.getHeight();

                                        if (viewWidth > 0 && viewHeight > 0 && viewWidth > viewHeight * 2) {
                                            Drawable customLogo = getDrawable(currentDrawable);

                                            if (customLogo != null && currentDrawable != customLogo) {
                                                logoView.setImageDrawable(customLogo);
                                            }

                                            logoView.getViewTreeObserver().removeOnPreDrawListener(this);
                                        }
                                    }
                                    return true;
                                }
                            });
                        }
                    } else if (child instanceof ViewGroup) {
                        queue.add((ViewGroup) child);
                    }
                }
            }
        });
    }
}
