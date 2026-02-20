package org.chromium.net.impl;

import org.chromium.net.URLRequest;

public abstract class CronetURLRequest extends URLRequest {

    /**
     * Method is added by patch.
     */
    public abstract String getHookedURL();
}
