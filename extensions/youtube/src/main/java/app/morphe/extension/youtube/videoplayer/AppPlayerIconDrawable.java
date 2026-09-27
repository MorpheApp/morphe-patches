/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Replaces an icon of the app's own player or Shorts controls with the selected icon style.
 * <p>
 * The patch moves the original drawable to a new name and puts a {@code <drawable class>}
 * pointing to a subclass in its place, so every place that loads the icon gets this wrapper.
 */
@SuppressWarnings("unused")
public abstract class AppPlayerIconDrawable extends DrawableWrapper {

    public static final class FullscreenEnter extends AppPlayerIconDrawable {
        public FullscreenEnter() {
            super("morphe_fullscreen_enter", "morphe_yt_player_full_enter");
        }
    }

    public static final class FullscreenEnterAlt extends AppPlayerIconDrawable {
        public FullscreenEnterAlt() {
            super("morphe_fullscreen_enter", "morphe_yt_player_full_enter_alt");
        }
    }

    public static final class FullscreenEnterPortrait extends AppPlayerIconDrawable {
        public FullscreenEnterPortrait() {
            super("morphe_fullscreen_enter", "morphe_yt_player_full_enter_portrait");
        }
    }

    public static final class FullscreenExit extends AppPlayerIconDrawable {
        public FullscreenExit() {
            super("morphe_fullscreen_exit", "morphe_yt_player_full_exit");
        }
    }

    public static final class FullscreenExitAlt extends AppPlayerIconDrawable {
        public FullscreenExitAlt() {
            super("morphe_fullscreen_exit", "morphe_yt_player_full_exit_alt");
        }
    }

    public static final class ShortsHeart extends AppPlayerIconDrawable {
        public ShortsHeart() {
            super(Dim.dp32, "morphe_shorts_heart", "morphe_youtube_shorts_heart_outline_32dp");
        }
    }

    public static final class ShortsHeartFill extends AppPlayerIconDrawable {
        public ShortsHeartFill() {
            super(Dim.dp32, "morphe_shorts_heart_fill", "morphe_youtube_shorts_heart_fill_32dp");
        }
    }

    public static final class ShortsHeartOff extends AppPlayerIconDrawable {
        public ShortsHeartOff() {
            super(Dim.dp32, "morphe_shorts_heart_fill", "morphe_youtube_shorts_heart_off_32dp");
        }
    }

    public static final class ShortsComment extends AppPlayerIconDrawable {
        public ShortsComment() {
            super(Dim.dp32, "morphe_shorts_comment", "morphe_youtube_shorts_comment_outline_32dp");
        }
    }

    public static final class ShortsSave extends AppPlayerIconDrawable {
        public ShortsSave() {
            super(Dim.dp32, "morphe_shorts_save", "morphe_youtube_shorts_save_outline_32dp");
        }
    }

    public static final class ShortsSaveFill extends AppPlayerIconDrawable {
        public ShortsSaveFill() {
            super(Dim.dp32, "morphe_shorts_save_fill", "morphe_youtube_shorts_save_fill_32dp");
        }
    }

    public static final class ShortsShare extends AppPlayerIconDrawable {
        public ShortsShare() {
            super(Dim.dp32, "morphe_shorts_share", "morphe_youtube_shorts_share_outline_32dp");
        }
    }

    public static final class ShortsRemix extends AppPlayerIconDrawable {
        public ShortsRemix() {
            super(Dim.dp32, "morphe_shorts_remix", "morphe_youtube_shorts_remix_outline_32dp");
        }
    }

    public static final class ShortsLike extends AppPlayerIconDrawable {
        public ShortsLike() {
            super(Dim.dp32, "morphe_shorts_like", "morphe_youtube_shorts_like_outline_32dp");
        }
    }

    public static final class ShortsLikeFill extends AppPlayerIconDrawable {
        public ShortsLikeFill() {
            super(Dim.dp32, "morphe_shorts_like_fill", "morphe_youtube_shorts_like_fill_32dp");
        }
    }

    public static final class ShortsDislike extends AppPlayerIconDrawable {
        public ShortsDislike() {
            super(Dim.dp32, "morphe_shorts_dislike", "morphe_youtube_shorts_dislike_outline_32dp");
        }
    }

    public static final class ShortsDislikeFill extends AppPlayerIconDrawable {
        public ShortsDislikeFill() {
            super(Dim.dp32, "morphe_shorts_dislike_fill", "morphe_youtube_shorts_dislike_fill_32dp");
        }
    }

    public static final class DelhiHeart extends AppPlayerIconDrawable {
        public DelhiHeart() {
            super(Dim.dp24, "morphe_shorts_heart", "morphe_yt_delhi_heart_outline_24dp");
        }
    }

    public static final class DelhiHeartFill extends AppPlayerIconDrawable {
        public DelhiHeartFill() {
            super(Dim.dp24, "morphe_shorts_heart_fill", "morphe_yt_delhi_heart_fill_white_24dp");
        }
    }

    public static final class DelhiComment extends AppPlayerIconDrawable {
        public DelhiComment() {
            super(Dim.dp24, "morphe_shorts_comment", "morphe_yt_delhi_comment_24dp");
        }
    }

    public static final class DelhiSave extends AppPlayerIconDrawable {
        public DelhiSave() {
            super(Dim.dp24, "morphe_shorts_save", "morphe_yt_delhi_bookmark_not_filled_24dp");
        }
    }

    public static final class DelhiSaveFill extends AppPlayerIconDrawable {
        public DelhiSaveFill() {
            super(Dim.dp24, "morphe_shorts_save_fill", "morphe_yt_delhi_bookmark_filled_24dp");
        }
    }

    public static final class DelhiShare extends AppPlayerIconDrawable {
        public DelhiShare() {
            super(Dim.dp24, "morphe_shorts_share", "morphe_yt_delhi_share_24dp");
        }
    }

    public static final class DelhiRemix extends AppPlayerIconDrawable {
        public DelhiRemix() {
            super(Dim.dp24, "morphe_shorts_remix", "morphe_yt_delhi_remix_24dp");
        }
    }

    public static final class DelhiLike extends AppPlayerIconDrawable {
        public DelhiLike() {
            super(Dim.dp24, "morphe_shorts_like", "morphe_yt_delhi_thumbs_up_not_filled_24dp");
        }
    }

    public static final class DelhiLikeFill extends AppPlayerIconDrawable {
        public DelhiLikeFill() {
            super(Dim.dp24, "morphe_shorts_like_fill", "morphe_yt_delhi_thumbs_up_filled_24dp");
        }
    }

    public static final class DelhiDislike extends AppPlayerIconDrawable {
        public DelhiDislike() {
            super(Dim.dp24, "morphe_shorts_dislike", "morphe_yt_delhi_thumbs_down_not_filled_24dp");
        }
    }

    public static final class DelhiDislikeFill extends AppPlayerIconDrawable {
        public DelhiDislikeFill() {
            super(Dim.dp24, "morphe_shorts_dislike_fill", "morphe_yt_delhi_thumbs_down_filled_24dp");
        }
    }

    private final String drawableName;
    // The style icons are plain vectors, while the Shorts originals are bitmaps with a shadow.
    private final boolean shortsStyled;
    private final int shortsSize;
    @Nullable
    private Bitmap shadow;

    private AppPlayerIconDrawable(String styleBaseName, String originalName) {
        super(null);
        drawableName = PlayerIcons.name(styleBaseName, originalName, originalName);
        shortsStyled = false;
        shortsSize = 0;
    }

    /**
     * @param shortsSize Size of the app's Shorts icon, which the style icon takes over.
     */
    private AppPlayerIconDrawable(int shortsSize, String styleBaseName, String originalName) {
        super(null);
        drawableName = PlayerIcons.shorts(styleBaseName, originalName);
        shortsStyled = !drawableName.equals(originalName);
        this.shortsSize = shortsSize;
    }

    // The original icon is tinted with a theme attribute, so it is loaded with the theme of the caller.
    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
                        @NonNull AttributeSet attrs, @Nullable Resources.Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);
        try {
            setDrawable(r.getDrawable(
                    ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, drawableName), theme));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not load player icon: " + drawableName, ex);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return shortsStyled ? shortsSize : super.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return shortsStyled ? shortsSize : super.getIntrinsicHeight();
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        shadow = null;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (shortsStyled && IconShadow.isAvailable()) {
            Drawable icon = getDrawable();
            Rect bounds = getBounds();
            if (shadow == null && icon != null && !bounds.isEmpty()) {
                shadow = IconShadow.build(icon, bounds.width(), bounds.height());
            }
            if (shadow != null) {
                canvas.drawBitmap(shadow, bounds.left, bounds.top, null);
            }
        }

        super.draw(canvas);
    }

    // Resources caches drawables by their constant state, and a copy made from it would be empty.
    @Nullable
    @Override
    public ConstantState getConstantState() {
        return null;
    }

    @NonNull
    @Override
    public Drawable mutate() {
        Drawable drawable = getDrawable();
        if (drawable != null) {
            drawable.mutate();
        }
        return this;
    }
}
