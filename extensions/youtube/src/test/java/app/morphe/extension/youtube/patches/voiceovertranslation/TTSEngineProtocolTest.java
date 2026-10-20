/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3879
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

public class TTSEngineProtocolTest {
    private static void invoke(String method, Class<?>[] types, Object... args) throws Exception {
        Method target = TTSEngine.class.getDeclaredMethod(method, types);
        target.setAccessible(true);
        try {
            target.invoke(TTSEngine.INSTANCE, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw ex;
        }
    }

    @Test
    public void acceptsValidUpgradeOnly() throws Exception {
        String valid = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n";
        invoke("readHttpUpgrade", new Class<?>[]{java.io.InputStream.class},
                new ByteArrayInputStream(valid.getBytes(StandardCharsets.US_ASCII)));

        String rejected = "HTTP/1.1 403 Forbidden\r\nX-Error: 101\r\n\r\n";
        assertThrows(IOException.class, () -> invoke("readHttpUpgrade",
                new Class<?>[]{java.io.InputStream.class},
                new ByteArrayInputStream(rejected.getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void collectsAudioUntilTurnEnd() throws Exception {
        byte[] end = "Path:turn.end".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        frames.write(new byte[]{(byte) 0x82, 5, 0, 0, 11, 22, 33});
        frames.write(0x81);
        frames.write(end.length);
        frames.write(end);

        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        invoke("collectAudio", new Class<?>[]{java.io.InputStream.class, ByteArrayOutputStream.class},
                new ByteArrayInputStream(frames.toByteArray()), audio);
        assertArrayEquals(new byte[]{11, 22, 33}, audio.toByteArray());
    }

    @Test
    public void rejectsUnexpectedCloseAndIncompleteStream() {
        Class<?>[] types = {java.io.InputStream.class, ByteArrayOutputStream.class};
        assertThrows(IOException.class, () -> invoke("collectAudio", types,
                new ByteArrayInputStream(new byte[]{(byte) 0x88, 0}), new ByteArrayOutputStream()));
        assertThrows(IOException.class, () -> invoke("collectAudio", types,
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
    }
}
