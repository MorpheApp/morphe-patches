package app.morphe.extension.youtube.patches;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class CaptionInputReplayTest {

    @Test
    public void preservesEveryInputByteIncludingNewlines() throws Exception {
        byte[] bytes = "<p>A\n\nB\n</p>".getBytes("UTF-8");
        CaptionInputReplay.Copy copy =
                CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 1024);
        assertTrue(copy.complete);
        assertArrayEquals(bytes, copy.bytes);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test
    public void oversizedInputStillReachesParserUnchanged() throws Exception {
        byte[] bytes = new byte[8192];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 31);
        assertFalse(copy.complete);
        assertTrue(copy.bytes.length <= 32);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test
    public void preservesEmptyAndExactLimitInput() throws Exception {
        assertTrue(CaptionInputReplay.copy(new ByteArrayInputStream(new byte[0]), 10).complete);
        byte[] bytes = new byte[10];
        CaptionInputReplay.Copy copy = CaptionInputReplay.copy(new ByteArrayInputStream(bytes), 10);
        assertTrue(copy.complete);
        assertArrayEquals(bytes, read(copy.input));
    }

    @Test(expected = IOException.class)
    public void preservesReadFailures() throws Exception {
        CaptionInputReplay.copy(
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("test failure");
                    }
                },
                1024);
    }

    private static byte[] read(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[113];
        int n;
        while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        return output.toByteArray();
    }
}
