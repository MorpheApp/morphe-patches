/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Request-scoped input repair. Native timing, sampling and view code are unchanged. */
@SuppressWarnings("unused")
public final class TranslatedRollingCaptionFix {
    private static final ThreadLocal<Boolean> translatedAsr = new ThreadLocal<>();

    private TranslatedRollingCaptionFix() {}

    public static void setRequest(Object request) {
        boolean active = false;
        try {
            active =
                    request
                                    instanceof
                                    TranslatedCaptionPensPatch.CaptionRequestInterface
                                            captionRequest
                            && captionRequest.patch_getCaptionTrack()
                                    instanceof
                                    AutoTranslateCaptionsPatch.CaptionTrackInterface track
                            && TranslatedRollingCaptionPolicy.isTranslatedAsr(
                                    track.patch_getVssId(), track.patch_getUrl());
        } catch (RuntimeException | LinkageError ignored) {
        }
        translatedAsr.set(active);
    }

    public static InputStream repairInput(InputStream input) throws IOException {
        boolean repair = Boolean.TRUE.equals(translatedAsr.get());
        if (input == null || (!repair && !StableCaptionBlocks.isCapturing())) return input;
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(input, CaptionXml.MAX_BYTES);
        if (!copy.complete) return copy.input;
        StableCaptionBlocks.capture(copy.bytes);
        byte[] repaired = repair ? TranslatedRollingCaptionPolicy.repair(copy.bytes) : copy.bytes;
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
}
