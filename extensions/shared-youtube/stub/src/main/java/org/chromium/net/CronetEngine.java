package org.chromium.net;

public abstract class CronetEngine {
    public abstract static class Builder {
        public abstract Builder setProxyOptions(ProxyOptions proxyOptions);
    }
}
