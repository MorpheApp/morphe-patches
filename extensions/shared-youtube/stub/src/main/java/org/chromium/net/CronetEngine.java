package org.chromium.net;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public abstract class CronetEngine {
    public abstract String getVersionString();

    public abstract URLConnection openConnection(URL url) throws IOException;

    public abstract static class Builder {
        public abstract Builder setProxyOptions(ProxyOptions proxyOptions);
    }
}
