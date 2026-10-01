package app.morphe.patches.music.interaction.jam

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the actual Android-free extension decoder against unsupported native wire forms. */
class JamQueueCommandTest {
    private fun varint(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var remaining = value
        while (remaining and -128 != 0) {
            out.write((remaining and 127) or 128)
            remaining = remaining ushr 7
        }
        out.write(remaining)
        return out.toByteArray()
    }

    private fun field(number: Int, bytes: ByteArray) =
        varint((number shl 3) or 2) + varint(bytes.size) + bytes

    @Test
    fun `single tracks decode but playlist downloaded and malformed targets do not`() {
        val source =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map {
                    File(
                        it,
                        "extensions/music/src/main/java/app/morphe/extension/music/jam/QueueCommand.java",
                    )
                }
                .first { it.isFile }
        val output = createTempDirectory("jam-command-test").toFile()
        try {
            assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                    .run(null, null, null, "--release", "11", "-d", output.path, source.path),
            )
            URLClassLoader(arrayOf(output.toURI().toURL()), null).use { loader ->
                val decoder = loader.loadClass("app.morphe.extension.music.jam.QueueCommand")
                fun decode(bytes: ByteArray) =
                    decoder.getMethod("decode", ByteArray::class.java).invoke(null, bytes)
                fun watch(bytes: ByteArray) =
                    decoder.getMethod("watchVideo", ByteArray::class.java).invoke(null, bytes)
                val video = field(1, "abcdefghijk".toByteArray())
                fun enqueue(target: ByteArray) =
                    field(163162354, field(1, target) + byteArrayOf(16, 2))
                assertNotNull(decode(enqueue(video)))
                assertNull(decode(enqueue(field(2, "PLplaylist".toByteArray()))))
                assertNull(decode(enqueue(video + field(2, "PLplaylist".toByteArray()))))
                assertNull(decode(enqueue(video + byteArrayOf(24, 1))))
                assertNull(decode(enqueue(video + video)))
                assertNull(decode(byteArrayOf(-1)))
                assertEquals("abcdefghijk", watch(field(48687757, video)))
                val playlistWatch = field(48687757, video + field(2, "PLplaylist".toByteArray()))
                assertEquals("abcdefghijk", watch(playlistWatch))
                assertNull(watch(field(48687757, field(2, "PLplaylist".toByteArray()))))
                assertNull(watch(field(48687757, video + video)))
                assertTrue(
                    decoder
                        .getMethod("isPlayback", ByteArray::class.java)
                        .invoke(null, playlistWatch) as Boolean
                )
            }
        } finally {
            output.deleteRecursively()
        }
    }
}
