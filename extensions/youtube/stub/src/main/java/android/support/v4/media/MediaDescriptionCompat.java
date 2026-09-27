/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2489
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package android.support.v4.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

// Used only while compiling; YouTube Music provides the real class at runtime.
public final class MediaDescriptionCompat {
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
