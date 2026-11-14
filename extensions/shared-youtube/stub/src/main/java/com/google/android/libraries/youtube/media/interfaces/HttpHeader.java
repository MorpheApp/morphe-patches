package com.google.android.libraries.youtube.media.interfaces;

/**
 * Header of the media requests. The class name is not obfuscated, but its fields are.
 * Not final, so it can be cast to the interface added during patching.
 */
@SuppressWarnings("unused")
public class HttpHeader {
    public HttpHeader(String name, String value) {
        throw new UnsupportedOperationException("Stub");
    }
}
