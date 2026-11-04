package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import defpackage.blie;
import defpackage.bjts;

public class ShortsNotificationsPatchTest {
    @Test
    public void identifiesSupportedReelWatchEndpoints() {
        FakePush current = new FakePush();
        current.c = new FakeRenderer(new FakeEndpoint(blie.b.d));
        assertTrue(ShortsNotificationsPatch.isShortsNotification(current));

        FakePush legacy = new FakePush();
        legacy.c = new FakeRenderer(new FakeEndpoint(bjts.b.d));
        assertTrue(ShortsNotificationsPatch.isShortsNotification(legacy));
    }

    @Test
    public void identifiesReelEndpointInSerializedPushProto() throws IOException {
        byte[] endpoint = fieldBytes(50, "reel".getBytes(StandardCharsets.UTF_8));
        byte[] navigation = fieldBytes(139608561, endpoint);
        byte[] renderer = fieldBytes(2, navigation);
        assertTrue(ShortsNotificationsPatch.isShortsNotification(
                new SerializedPush(fieldBytes(77819057, renderer))));
    }

    @Test
    public void ignoresOtherPushTypesAndVideoEndpoints() {
        FakePush regularVideo = new FakePush();
        regularVideo.c = new FakeRenderer(new FakeEndpoint(new Object()));
        assertFalse(ShortsNotificationsPatch.isShortsNotification(regularVideo));

        FakePush otherPush = new FakePush();
        otherPush.b = 1;
        otherPush.c = new FakeRenderer(new FakeEndpoint(blie.b.d));
        assertFalse(ShortsNotificationsPatch.isShortsNotification(otherPush));
    }

    public static final class FakePush {
        public int b = 77819057;
        public Object c;
    }

    public static final class FakeRenderer {
        public Object f;
        FakeRenderer(Object navigationEndpoint) { f = navigationEndpoint; }
    }

    public static final class FakeEndpoint {
        public FakeFieldSet l;
        FakeEndpoint(Object descriptor) { l = new FakeFieldSet(descriptor); }
    }

    public static final class FakeFieldSet {
        private final Object descriptor;
        FakeFieldSet(Object descriptor) { this.descriptor = descriptor; }
        public boolean n(Object descriptor) { return this.descriptor == descriptor; }
    }

    public static final class SerializedPush {
        public int b = 77819057;
        private final byte[] serialized;
        SerializedPush(byte[] serialized) { this.serialized = serialized; }
        public byte[] toByteArray() { return serialized; }
    }

    private static byte[] fieldBytes(int fieldNumber, byte[] value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeByteArray(fieldNumber, value);
        output.flush();
        return bytes.toByteArray();
    }
}
