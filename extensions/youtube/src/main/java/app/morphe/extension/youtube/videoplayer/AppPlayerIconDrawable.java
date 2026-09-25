/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.content.res.Resources;
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

/**
 * Replaces an icon of the app's own player controls with the selected icon style.
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

    private final String drawableName;

    private AppPlayerIconDrawable(String styleBaseName, String originalName) {
        super(null);
        drawableName = PlayerIcons.name(styleBaseName, originalName, originalName);
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
