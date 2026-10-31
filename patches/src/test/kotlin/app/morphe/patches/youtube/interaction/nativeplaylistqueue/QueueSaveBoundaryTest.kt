/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import com.google.protobuf.GeneratedMessageLite
import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueSaveBoundaryTest {
    // This error callback logs through Android's Logger. Keep only that boundary isolated;
    // happy-path mutations and wire formats are direct extension tests.
    @Test
    fun `unsupported destination follows native error path instead of saving a partial queue`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions/youtube/src/main/proto").isDirectory }
        val output = createTempDirectory("playlist-queue-save").toFile()
        val generated = File(root,
            "extensions/youtube/build/generated/java/generateReleaseProto/java/app/morphe/extension/youtube/innertube")
        val protobuf = File(GeneratedMessageLite::class.java.protectionDomain.codeSource.location.toURI())
        val logger = File(output, "Logger.java").apply {
            writeText("""
                package app.morphe.extension.shared;
                public class Logger {
                    public static void printException(java.util.function.Supplier<String> message, Throwable error) {}
                }
            """.trimIndent())
        }
        val nullable = File(output, "Nullable.java").apply {
            writeText("package androidx.annotation; public @interface Nullable {}")
        }
        val strings = File(output, "StringRef.java").apply {
            writeText("""
                package app.morphe.extension.shared;
                public class StringRef {
                    public static String str(String name, Object... args) { return name; }
                }
            """.trimIndent())
        }
        val duration = File(output, "NativePlaylistQueuePatch.java").apply {
            writeText("""
                package app.morphe.extension.youtube.patches.queue;
                public class NativePlaylistQueuePatch {
                    static int queueUndoDuration(String token) {
                        throw new AssertionError("Save error handling must not create removal feedback");
                    }
                }
            """.trimIndent())
        }
        val fixture = File(output, "SaveFixture.java").apply {
            writeText("""
                package app.morphe.extension.youtube.patches.queue;
                import java.util.*;
                import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.*;
                public class SaveFixture {
                    static class Picker implements QueueSaveActions.Picker {
                        List<String> videoIds;
                        Picker(List<String> ids) { videoIds = ids; }
                        public List<String> patch_getQueueSaveVideoIds() { return videoIds; }
                        public com.google.protobuf.MessageLite patch_parseQueueSaveResponse(byte[] bytes) {
                            try { return QueueSaveResponse.parseFrom(bytes); }
                            catch (Exception error) { throw new AssertionError(error); }
                        }
                    }
                    public static void rejectUnsupportedDestination() {
                        var destination = QueueSaveDestinationItem.newBuilder().setPlaylistAddToOptionRenderer(
                            QueueSaveDestination.newBuilder().setAddEndpoint(NavigationEndpoint.getDefaultInstance()));
                        var unsupported = QueueSaveResponse.newBuilder().addContents(
                            QueueSaveResponseItem.newBuilder().setAddToPlaylistRenderer(
                                QueueSavePickerRenderer.newBuilder().addPlaylists(destination))).build();
                        var result = (QueueSaveResponse) QueueSaveActions.onPickerResponse(
                            new Picker(List.of("abcdefghijk", "lmnopqrstuv")), unsupported);
                        if (result.getContentsCount() != 0) {
                            throw new AssertionError("Failed batch binding must follow the empty native error path");
                        }
                    }
                }
            """.trimIndent())
        }
        try {
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-classpath", protobuf.path, "-d", output.path,
                File(root, "extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/queue/QueueSaveActions.java").path,
                File(generated, "IconOuterClass.java").path,
                File(generated, "queue/PlaylistQueueRenderer.java").path, logger.path, nullable.path, strings.path, duration.path,
                File(root, "extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/queue/QueueSaveFeedback.java").path, fixture.path))
            URLClassLoader(arrayOf(output.toURI().toURL()), GeneratedMessageLite::class.java.classLoader).use { loader ->
                loader.loadClass("app.morphe.extension.youtube.patches.queue.SaveFixture")
                    .getMethod("rejectUnsupportedDestination").invoke(null)
            }
        } finally {
            output.deleteRecursively()
        }
    }
}
