/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.requests.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.security.Permission;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Base class for an {@link HttpURLConnection} that forwards operations to another connection.
 */
abstract class DelegatingHttpURLConnection extends HttpURLConnection {
    /**
     * Current connection delegate. A subclass may replace it before request I/O starts.
     */
    protected HttpURLConnection delegate;

    DelegatingHttpURLConnection(HttpURLConnection delegate) {
        super(delegate.getURL());
        this.delegate = delegate;
    }

    /**
     * Called before an operation that can wait for response headers.
     */
    protected void beforeResponse() throws IOException {
    }

    private boolean beforeResponseQuietly() {
        try {
            beforeResponse();
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    @Override
    public void connect() throws IOException {
        delegate.connect();
    }

    @Override
    public void disconnect() {
        delegate.disconnect();
    }

    @Override
    public boolean usingProxy() {
        return delegate.usingProxy();
    }

    @Override
    public URL getURL() {
        return delegate.getURL();
    }

    @Override
    public void setConnectTimeout(int timeout) {
        super.setConnectTimeout(timeout);
        delegate.setConnectTimeout(timeout);
    }

    @Override
    public int getConnectTimeout() {
        return super.getConnectTimeout();
    }

    @Override
    public void setReadTimeout(int timeout) {
        super.setReadTimeout(timeout);
        delegate.setReadTimeout(timeout);
    }

    @Override
    public int getReadTimeout() {
        return super.getReadTimeout();
    }

    @Override
    public int getContentLength() {
        return beforeResponseQuietly() ? delegate.getContentLength() : -1;
    }

    @Override
    public long getContentLengthLong() {
        return beforeResponseQuietly() ? delegate.getContentLengthLong() : -1;
    }

    @Override
    public String getContentType() {
        return beforeResponseQuietly() ? delegate.getContentType() : null;
    }

    @Override
    public String getContentEncoding() {
        return beforeResponseQuietly() ? delegate.getContentEncoding() : null;
    }

    @Override
    public long getExpiration() {
        return beforeResponseQuietly() ? delegate.getExpiration() : 0;
    }

    @Override
    public long getDate() {
        return beforeResponseQuietly() ? delegate.getDate() : 0;
    }

    @Override
    public long getLastModified() {
        return beforeResponseQuietly() ? delegate.getLastModified() : 0;
    }

    @Override
    public String getHeaderField(String name) {
        return beforeResponseQuietly() ? delegate.getHeaderField(name) : null;
    }

    @Override
    public Map<String, List<String>> getHeaderFields() {
        return beforeResponseQuietly() ? delegate.getHeaderFields() : Collections.emptyMap();
    }

    @Override
    public int getHeaderFieldInt(String name, int defaultValue) {
        return beforeResponseQuietly()
                ? delegate.getHeaderFieldInt(name, defaultValue)
                : defaultValue;
    }

    @Override
    public long getHeaderFieldLong(String name, long defaultValue) {
        return beforeResponseQuietly()
                ? delegate.getHeaderFieldLong(name, defaultValue)
                : defaultValue;
    }

    @Override
    public long getHeaderFieldDate(String name, long defaultValue) {
        return beforeResponseQuietly()
                ? delegate.getHeaderFieldDate(name, defaultValue)
                : defaultValue;
    }

    @Override
    public String getHeaderFieldKey(int position) {
        return beforeResponseQuietly() ? delegate.getHeaderFieldKey(position) : null;
    }

    @Override
    public String getHeaderField(int position) {
        return beforeResponseQuietly() ? delegate.getHeaderField(position) : null;
    }

    @Override
    public Object getContent() throws IOException {
        beforeResponse();
        return delegate.getContent();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Object getContent(Class[] classes) throws IOException {
        beforeResponse();
        return delegate.getContent(classes);
    }

    @Override
    public Permission getPermission() throws IOException {
        return delegate.getPermission();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        beforeResponse();
        return delegate.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return delegate.getOutputStream();
    }

    @Override
    public void setDoInput(boolean doInput) {
        delegate.setDoInput(doInput);
    }

    @Override
    public boolean getDoInput() {
        return delegate.getDoInput();
    }

    @Override
    public void setDoOutput(boolean doOutput) {
        delegate.setDoOutput(doOutput);
    }

    @Override
    public boolean getDoOutput() {
        return delegate.getDoOutput();
    }

    @Override
    public void setAllowUserInteraction(boolean allowUserInteraction) {
        delegate.setAllowUserInteraction(allowUserInteraction);
    }

    @Override
    public boolean getAllowUserInteraction() {
        return delegate.getAllowUserInteraction();
    }

    @Override
    public void setUseCaches(boolean useCaches) {
        delegate.setUseCaches(useCaches);
    }

    @Override
    public boolean getUseCaches() {
        return delegate.getUseCaches();
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
        delegate.setIfModifiedSince(ifModifiedSince);
    }

    @Override
    public long getIfModifiedSince() {
        return delegate.getIfModifiedSince();
    }

    @Override
    public boolean getDefaultUseCaches() {
        return delegate.getDefaultUseCaches();
    }

    @Override
    public void setDefaultUseCaches(boolean defaultUseCaches) {
        delegate.setDefaultUseCaches(defaultUseCaches);
    }

    @Override
    public void setRequestProperty(String key, String value) {
        delegate.setRequestProperty(key, value);
    }

    @Override
    public void addRequestProperty(String key, String value) {
        delegate.addRequestProperty(key, value);
    }

    @Override
    public String getRequestProperty(String key) {
        return delegate.getRequestProperty(key);
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        return delegate.getRequestProperties();
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
        delegate.setFixedLengthStreamingMode(contentLength);
    }

    @Override
    public void setFixedLengthStreamingMode(long contentLength) {
        delegate.setFixedLengthStreamingMode(contentLength);
    }

    @Override
    public void setChunkedStreamingMode(int chunkLength) {
        delegate.setChunkedStreamingMode(chunkLength);
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
        delegate.setInstanceFollowRedirects(followRedirects);
    }

    @Override
    public boolean getInstanceFollowRedirects() {
        return delegate.getInstanceFollowRedirects();
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
        delegate.setRequestMethod(method);
    }

    @Override
    public String getRequestMethod() {
        return delegate.getRequestMethod();
    }

    @Override
    public int getResponseCode() throws IOException {
        beforeResponse();
        return delegate.getResponseCode();
    }

    @Override
    public String getResponseMessage() throws IOException {
        beforeResponse();
        return delegate.getResponseMessage();
    }

    @Override
    public InputStream getErrorStream() {
        return beforeResponseQuietly() ? delegate.getErrorStream() : null;
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
