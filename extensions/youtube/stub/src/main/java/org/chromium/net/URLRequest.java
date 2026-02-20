package org.chromium.net;

public abstract class URLRequest {
    public abstract class Builder {
        public abstract Builder addHeader(String name, String value);
        public abstract URLRequest build();
    }
}
