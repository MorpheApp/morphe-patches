/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4316
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import com.google.android.libraries.youtube.media.interfaces.HttpHeader;

import java.util.ArrayList;

/**
 * Added to the media request class of the app, whose fields are obfuscated.
 * Native code makes these requests, and the app starts them in Java.
 */
public interface MediaHttpRequest {
    // Methods are added during patching.
    @Nullable
    String patch_getUrl();

    @Nullable
    byte[] patch_getBody();

    @Nullable
    ArrayList<HttpHeader> patch_getHeaders();

    /**
     * @return A copy of this request with another body and other headers.
     */
    MediaHttpRequest patch_withBodyAndHeaders(byte[] body, @Nullable ArrayList<HttpHeader> headers);
}
