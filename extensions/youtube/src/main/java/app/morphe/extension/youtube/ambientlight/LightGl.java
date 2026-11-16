/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5222
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.ambientlight;

import android.graphics.Color;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Draws the light on a surface with a small program of its own on the GPU. A hardware canvas
 * draws through the render thread of the app, which costs about as much as the copy of the
 * video picture does. This draws on the calling thread at a fraction of that, with the same
 * result: the small light scaled up, mixed with what is under and over it, and dithered.
 * <p>
 * There is one for each surface. They share the context and the program, which stay for the
 * life of the app once they are made. All methods must be called from the light's worker
 * thread. A method that returns false has let go of the surface, which can then be drawn with
 * a canvas.
 */
final class LightGl {
    private static final String VERTEX_SHADER = "#version 300 es\n"
            + "in vec2 position;\n"
            + "out vec2 lightPosition;\n"
            + "void main() {\n"
            + "  lightPosition = position;\n"
            + "  gl_Position = vec4(position.x * 2.0 - 1.0, 1.0 - position.y * 2.0, 0.0, 1.0);\n"
            + "}\n";
    private static final String FRAGMENT_SHADER = "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform sampler2D light;\n"
            + "uniform float strength;\n"
            + "uniform vec3 under;\n"
            + "uniform vec4 cover;\n"
            + "in vec2 lightPosition;\n"
            + "out vec4 color;\n"
            + "void main() {\n"
            + "  vec3 mixed = mix(under, texture(light, lightPosition).rgb, strength);\n"
            + "  mixed = mix(mixed, cover.rgb, cover.a);\n"
            // A dark gradient shows steps on an 8 bit screen without noise of less than one step.
            + "  float noise = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));\n"
            + "  color = vec4(mixed + (noise - 0.5) / 255.0, 1.0);\n"
            + "}\n";
    private static final int[] CONFIG_ATTRIBUTES = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE};
    private static final int[] CONTEXT_ATTRIBUTES = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
    private static final int[] NO_ATTRIBUTES = {EGL14.EGL_NONE};
    /** Failed tries to make the context or the program after which the device counts as unable. */
    private static final int MAX_SETUP_FAILURES = 3;

    /** Set once the device cannot do this, so that no surface tries again. */
    private static volatile boolean unsupported;
    private static int setupFailures;
    private static EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private static EGLContext context = EGL14.EGL_NO_CONTEXT;
    private static EGLConfig config;
    private static int program;
    private static int strengthUniform;
    private static int underUniform;
    private static int coverUniform;
    private static int textureWidth;
    private static int textureHeight;
    private static int textureVersion = -1;

    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    /** Counts the surfaces of the view. The surface object stays the same when its buffers are new. */
    private int surfaceGeneration = -1;

    static boolean isSupported() {
        return !unsupported;
    }

    /**
     * Draws the light in the regions of the surface that show it, and leaves the rest see-through.
     *
     * @param generation   Changes when the view has a new surface.
     * @param light        The light, 4 half floats for each of its pixels.
     * @param lightVersion Changes when the light does.
     * @param strength     The share of the light over what is under it, from 0 to 1.
     * @param pageColor    What is under the light of a page.
     * @param barColor     What is under the light of the player.
     * @param coverColor   What lies over the light of a page, to keep text readable.
     * @return False if the surface cannot be drawn this way.
     */
    boolean draw(Surface surface, int generation, int width, int height, short[] light, int lightVersion,
                 int lightWidth, int lightHeight, List<AmbientLightController.Region> regions, float strength,
                 int pageColor, int barColor, int coverColor) {
        try {
            if (!prepare(surface, generation)) return false;

            if (lightVersion != textureVersion || lightWidth != textureWidth || lightHeight != textureHeight) {
                ShortBuffer pixels = ShortBuffer.wrap(light);
                if (lightWidth == textureWidth && lightHeight == textureHeight) {
                    GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, lightWidth, lightHeight, GLES20.GL_RGBA,
                            GLES30.GL_HALF_FLOAT, pixels);
                } else {
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, lightWidth, lightHeight, 0,
                            GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, pixels);
                    textureWidth = lightWidth;
                    textureHeight = lightHeight;
                }
                textureVersion = lightVersion;
            }

            GLES20.glViewport(0, 0, width, height);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
            GLES20.glUniform1f(strengthUniform, strength);
            for (AmbientLightController.Region region : regions) {
                final int under = region.onPage() ? pageColor : barColor;
                GLES20.glUniform3f(underUniform, Color.red(under) / 255f, Color.green(under) / 255f,
                        Color.blue(under) / 255f);
                GLES20.glUniform4f(coverUniform, Color.red(coverColor) / 255f, Color.green(coverColor) / 255f,
                        Color.blue(coverColor) / 255f, region.onPage() ? Color.alpha(coverColor) / 255f : 0f);
                // The light spans the surface, and each region shows its part of it. Rows count from the bottom.
                GLES20.glScissor(region.left(), height - region.bottom(), region.right() - region.left(),
                        region.bottom() - region.top());
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            }
            return swap();
        } catch (RuntimeException ex) {
            // A surface that was destroyed in the meantime.
            Logger.printDebug(() -> "Light surface went away while it was drawn", ex);
            releaseSurface();
            return false;
        }
    }

    /**
     * Takes the light off the surface.
     *
     * @return False if the surface cannot be drawn this way.
     */
    boolean clear(Surface surface, int generation, int width, int height) {
        try {
            if (!prepare(surface, generation)) return false;
            GLES20.glViewport(0, 0, width, height);
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return swap();
        } catch (RuntimeException ex) {
            Logger.printDebug(() -> "Light surface went away while it was cleared", ex);
            releaseSurface();
            return false;
        }
    }

    private boolean swap() {
        if (EGL14.eglSwapBuffers(display, eglSurface)) return true;
        releaseSurface();
        return false;
    }

    /**
     * Makes the surface the target of the calls that follow.
     */
    private boolean prepare(Surface surface, int generation) {
        if (unsupported) return false;
        if (context == EGL14.EGL_NO_CONTEXT && !createContext()) return setupFailed("context");

        if (eglSurface == EGL14.EGL_NO_SURFACE || generation != surfaceGeneration) {
            releaseSurface();
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, NO_ATTRIBUTES, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false;
            surfaceGeneration = generation;
        }
        // Another surface can have been the target since the last draw.
        if (!eglSurface.equals(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))
                || !context.equals(EGL14.eglGetCurrentContext())) {
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                releaseSurface();
                return false;
            }
            // The worker never waits for the screen.
            EGL14.eglSwapInterval(display, 0);
        }
        if (program == 0 && !createProgram()) {
            releaseSurface();
            return setupFailed("program");
        }
        return true;
    }

    /**
     * A device that cannot make the context or the program is not asked again. A failure can
     * also be one of the moment, such as too little memory, so it takes more than one.
     */
    private static boolean setupFailed(String what) {
        if (++setupFailures >= MAX_SETUP_FAILURES) {
            unsupported = true;
            Logger.printDebug(() -> "The light is not drawn with OpenGL ES on this device: " + what);
        }
        return false;
    }

    private static boolean createContext() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, version, 0, version, 1)) return false;

        // The first config can have more than 8 bits for a color, and then 2 for alpha.
        EGLConfig[] configs = new EGLConfig[32];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, CONFIG_ATTRIBUTES, 0, configs, 0, configs.length, count, 0)) return false;
        config = null;
        int[] size = new int[1];
        for (int i = 0; i < count[0] && config == null; i++) {
            boolean exact = true;
            for (int attribute : new int[]{EGL14.EGL_RED_SIZE, EGL14.EGL_GREEN_SIZE, EGL14.EGL_BLUE_SIZE,
                    EGL14.EGL_ALPHA_SIZE}) {
                exact &= EGL14.eglGetConfigAttrib(display, configs[i], attribute, size, 0) && size[0] == 8;
            }
            if (exact) config = configs[i];
        }
        if (config == null) return false;

        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, CONTEXT_ATTRIBUTES, 0);
        return context != EGL14.EGL_NO_CONTEXT;
    }

    /**
     * The context is current. Everything the draws need is set once here, as nothing else uses the context.
     */
    private static boolean createProgram() {
        final int vertexShader = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        final int fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        if (vertexShader == 0 || fragmentShader == 0) return false;
        final int linked = GLES20.glCreateProgram();
        GLES20.glAttachShader(linked, vertexShader);
        GLES20.glAttachShader(linked, fragmentShader);
        GLES20.glBindAttribLocation(linked, 0, "position");
        GLES20.glLinkProgram(linked);
        int[] status = new int[1];
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) return false;

        GLES20.glUseProgram(linked);
        strengthUniform = GLES20.glGetUniformLocation(linked, "strength");
        underUniform = GLES20.glGetUniformLocation(linked, "under");
        coverUniform = GLES20.glGetUniformLocation(linked, "cover");
        GLES20.glUniform1i(GLES20.glGetUniformLocation(linked, "light"), 0);

        // One rectangle over all of the surface.
        FloatBuffer corners = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        corners.put(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f}).position(0);
        int[] names = new int[1];
        GLES20.glGenBuffers(1, names, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, names[0]);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 32, corners, GLES20.GL_STATIC_DRAW);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glGenTextures(1, names, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, names[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false;
        program = linked;
        return true;
    }

    private static int compile(int type, String source) {
        final int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        return status[0] == 0 ? 0 : shader;
    }

    /**
     * Lets go of the surface, which can then be drawn in another way, or be destroyed.
     */
    void releaseSurface() {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return;
        try {
            // Not while it is the target. Another surface can be the target by now, and stays it.
            if (eglSurface.equals(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            EGL14.eglDestroySurface(display, eglSurface);
        } catch (RuntimeException ex) {
            Logger.printDebug(() -> "Light surface was not released", ex);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
    }
}
