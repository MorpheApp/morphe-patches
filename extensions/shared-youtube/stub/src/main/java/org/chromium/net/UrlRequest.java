package org.chromium.net;

@SuppressWarnings("unused")
public abstract class UrlRequest {
    public abstract static class Builder {
        public abstract Builder addHeader(String name, String value);
        public abstract UrlRequest build();
    }
}
