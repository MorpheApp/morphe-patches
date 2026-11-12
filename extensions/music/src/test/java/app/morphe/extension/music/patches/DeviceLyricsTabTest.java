package app.morphe.extension.music.patches;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceLyricsTabTest {
    private byte[] tab(boolean enabled, String title) throws Exception {
        return DeviceLyricsTab.serializeTab(enabled, title);
    }

    @Test public void deviceFilesGetLyricsTabAndNoDownloadsOrNetworkEndpoint() throws Exception {
        byte[] bytes = tab(true, "Lyrics");
        assertNotNull("The Songs-only sideloaded response needs a second tab", bytes);
        ProtoMessage renderer = new ProtoMessage(bytes).messageField(58174010);
        assertEquals("Lyrics", renderer.stringField(2));
        assertEquals("morphe_device_lyrics", renderer.stringField(11));
        assertTrue("No browse or downloads endpoint", renderer.messageField(1).isEmpty());
        assertTrue("No queue content masquerading as lyrics", renderer.messageField(4).isEmpty());
        assertEquals("Do not steal the selected Songs tab", 0, renderer.varintField(3));
        assertEquals(0, renderer.varintField(20));
    }

    @Test public void disabledDoesNotCreateATab() throws Exception {
        assertNull(tab(false, "Lyrics"));
    }

    @Test public void titleIsLocalizedAndModelDoesNotCarryTrackIdentity() throws Exception {
        ProtoMessage renderer = new ProtoMessage(tab(true, "Letras")).messageField(58174010);
        assertEquals("Letras", renderer.stringField(2));
        assertEquals("morphe_device_lyrics", renderer.stringField(11));
    }
}
