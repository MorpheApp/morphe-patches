/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.OfflineVideoExporter;

/**
 * Last resort for receivers that cannot play a fragmented file: downloads the video and audio stream,
 * and copies them into a regular MP4 file without decoding. Playback starts only once the whole video
 * is downloaded.
 */
final class CastMediaFile {

    interface Cancel {
        /**
         * @return If the file is no longer needed.
         */
        boolean isCancelled();
    }

    /**
     * Thrown when the file is no longer needed.
     */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelled");
        }
    }

    interface Progress {
        /**
         * @param fraction From 0 to 1, over the download and the mux.
         */
        void onProgress(float fraction);
    }

    private static final float DOWNLOAD_PROGRESS_SHARE = 0.9f;
    private static final int MUX_PROGRESS_SAMPLES = 500;
    /**
     * Video and audio samples per second of video, about, to estimate the mux progress.
     */
    private static final int SAMPLES_PER_SECOND = 75;

    private CastMediaFile() {
    }

    /**
     * Blocks the calling thread.
     *
     * @param folder Folder for the file. Any previous files in it are deleted.
     */
    static File create(CastStreams streams, CastStreams.Stream video, CastStreams.Stream audio,
                       File folder, Progress progress, Cancel cancel) throws IOException {
        deleteFiles(folder);
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("Could not create folder: " + folder);
        }
        File videoFile = new File(folder, streams.videoId + ".video");
        File audioFile = new File(folder, streams.videoId + ".audio");
        File output = new File(folder, streams.videoId + ".mp4");

        final long total = Math.max(1, video.contentLength + audio.contentLength);
        download(streams, video, videoFile, 0, total, progress, cancel);
        download(streams, audio, audioFile, video.contentLength, total, progress, cancel);
        mux(videoFile, audioFile, output, streams.durationSeconds, progress, cancel);
        deleteFile(videoFile);
        deleteFile(audioFile);
        return output;
    }

    private static void download(CastStreams streams, CastStreams.Stream stream, File destination,
                                 long done, long total, Progress progress, Cancel cancel) throws IOException {
        try (OutputStream file = new FileOutputStream(destination)) {
            OutputStream counting = new OutputStream() {
                long written = done;

                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    if (cancel.isCancelled()) {
                        throw new CancelledException();
                    }
                    file.write(buffer, offset, length);
                    written += length;
                    progress.onProgress(DOWNLOAD_PROGRESS_SHARE * written / total);
                }
            };
            CastStreamServer.copyUpstream(stream.url, streams.userAgent, 0, stream.contentLength, counting, null);
        }
    }

    /**
     * The muxer writes the sample index at the end of the file. The Default Media Receiver reads
     * it from there with a range request, so the file is not rewritten to move the index first.
     */
    private static void mux(File videoFile, File audioFile, File output, double durationSeconds,
                            Progress progress, Cancel cancel) throws IOException {
        List<MediaExtractor> extractors = new ArrayList<>();
        List<MediaFormat> formats = new ArrayList<>();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(output,
                ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                        | ParcelFileDescriptor.MODE_READ_WRITE)) {
            for (File input : new File[]{videoFile, audioFile}) {
                MediaExtractor extractor = new MediaExtractor();
                extractors.add(extractor);
                extractor.setDataSource(input.getAbsolutePath());
                extractor.selectTrack(0);
                formats.add(extractor.getTrackFormat(0));
            }

            final double expectedSamples = Math.max(1, durationSeconds * SAMPLES_PER_SECOND);
            final long[] samples = {0};
            OfflineVideoExporter.mux(extractors, formats, descriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, () -> {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Mux cancelled");
                        }
                        if (cancel.isCancelled()) {
                            throw new CancelledException();
                        }
                        if (++samples[0] % MUX_PROGRESS_SAMPLES == 0) {
                            progress.onProgress(DOWNLOAD_PROGRESS_SHARE + (1 - DOWNLOAD_PROGRESS_SHARE)
                                    * (float) Math.min(1, samples[0] / expectedSamples));
                        }
                    });
        } finally {
            for (MediaExtractor extractor : extractors) {
                extractor.release();
            }
        }
    }

    static void deleteFiles(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFile(file);
            }
        }
    }

    private static void deleteFile(File file) {
        if (file.exists() && !file.delete()) {
            Logger.printInfo(() -> "Could not delete: " + file);
        }
    }
}
