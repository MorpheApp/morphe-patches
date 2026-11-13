package org.chromium.net;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

@SuppressWarnings("unused")
public abstract class CronetEngine {
    public abstract String getVersionString();

    @SuppressWarnings("RedundantThrows")
    public abstract URLConnection openConnection(URL url) throws IOException;

    public abstract static class Builder {
        @SuppressWarnings("UnusedReturnValue")
        public abstract Builder setProxyOptions(ProxyOptions proxyOptions);
    }
}
