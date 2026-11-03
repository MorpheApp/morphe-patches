/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3837
 * https://github.com/MorpheApp/morphe-patches/pull/3891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import org.w3c.dom.Document;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import app.morphe.extension.shared.Logger;


@SuppressWarnings("unused")
public class TranslatedCaptionPensPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface CaptionRequestInterface {
        // Method is added during patching.

        /**
         * @return The requested object. A caption track if this is a caption request.
         */
        @Nullable
        Object patch_getCaptionTrack();
    }

    /**
     * Pen id that is never declared by a timed text document.
     * Looking it up returns the default pen, which uses the user caption style.
     */
    private static final int DEFAULT_PEN_ID = Integer.MIN_VALUE;

    /**
     * If the caption track parsed on the current thread is auto-translated.
     * Caption tracks are parsed on background threads, and different tracks can be parsed at the same time.
     */
    private static final ThreadLocal<Boolean> isAutoTranslatedTrack = new ThreadLocal<>();

    /**
     * If the caption track parsed on the current thread is an auto-translated automatic caption track.
     */
    private static final ThreadLocal<Boolean> isTranslatedAutomaticTrack = new ThreadLocal<>();

    /**
     * Injection point.
     *
     * Called before a request response or a cached caption file is parsed.
     *
     * @param request Caption request, or any other request handled by the same code.
     */
    public static void setParsedRequest(@Nullable Object request) {
        boolean translated = false;
        boolean automatic = false;
        Object parsedTrack = null;
        try {
            if (request instanceof CaptionRequestInterface captionRequest
                    && captionRequest.patch_getCaptionTrack() instanceof PreferredCaptionLanguagePatch.CaptionTrackInterface track) {
                parsedTrack = track;
                String vssId = track.patch_getVssId();
                // Auto-translated vss ids are "t" + source vss id + "." + language code.
                translated = vssId.startsWith("t");
                automatic = track instanceof AutoTranslateCaptionsPatch.CaptionTrackInterface autoTrack
                        && AutoTranslateCaptionsPatch.isAutomaticCaption(vssId, autoTrack.patch_getUrl());
            }
        } catch (RuntimeException | LinkageError ex) {
            Logger.printException(() -> "setParsedRequest failure", ex);
        }
        // A failed native getter must not leave the previous track state enabled.
        isAutoTranslatedTrack.set(translated);
        isTranslatedAutomaticTrack.set(translated && automatic);
        CaptionTracks.setTrack(parsedTrack);
        StableCaptionBlocks.setAutomaticTrack(automatic);
    }

    /**
     * Injection point.
     *
     * Called before a caption track file is decoded.
     */
    public static InputStream repairInput(@Nullable InputStream input) throws IOException {
        boolean repair = Boolean.TRUE.equals(isTranslatedAutomaticTrack.get());
        if (input == null || (!repair && !StableCaptionBlocks.isCapturing() && !CaptionTracks.observing())) return input;
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(input, CaptionXml.MAX_BYTES);
        if (!copy.complete) return copy.input;
        Document document = CaptionXml.read(copy.bytes);
        // Capture the source captions before the repair removes events from the document.
        StableCaptionBlocks.capture(document);
        byte[] repaired = repair
                ? TranslatedRollingCaptionPolicy.repair(copy.bytes, document)
                : copy.bytes;
        if (repaired == copy.bytes) return copy.input;
        return new FilterInputStream(new ByteArrayInputStream(repaired)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    input.close();
                }
            }
        };
    }

    /**
     * Injection point.
     *
     * Auto-translated tracks keep the pen ids of the original track, but the translated text
     * does not line up with the original styled segments. Channels that use an invisible pen
     * for padding characters can end up with the entire translated line using that pen,
     * which shows only the caption background.
     *
     * @param penId Pen id of a timed text element.
     * @return The pen id to use.
     */
    public static int getPenId(int penId) {
        return Boolean.TRUE.equals(isAutoTranslatedTrack.get())
                ? DEFAULT_PEN_ID
                : penId;
    }
}
