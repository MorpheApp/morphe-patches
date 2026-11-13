/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4316
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

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

    /**
     * @return The headers of the request, each a {@link MediaHttpHeader}.
     */
    @Nullable
    ArrayList<Object> patch_getHeaders();

    /**
     * @return A copy of this request with another body.
     */
    MediaHttpRequest patch_withBody(byte[] body);

    /**
     * @param headers Headers made with {@link #patch_newHeader(String, String)}, or of a request.
     * @return A copy of this request with another body and other headers.
     */
    MediaHttpRequest patch_withBodyAndHeaders(byte[] body, ArrayList<Object> headers);

    /**
     * @return A new header of the type the requests have.
     */
    Object patch_newHeader(String name, String value);
}
