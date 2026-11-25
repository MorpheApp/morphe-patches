package android.support.v4.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

// Used only while compiling; YouTube Music provides the real class at runtime.
@SuppressWarnings("unused")
public final class MediaDescriptionCompat {
    // Media description extras.
    public final Bundle f;

    public MediaDescriptionCompat(
            String mediaId,
            CharSequence title,
            CharSequence subtitle,
            CharSequence description,
            Bitmap icon,
            Uri iconUri,
            Bundle extras,
            Uri mediaUri) {
        throw new UnsupportedOperationException("Stub");
    }
}
