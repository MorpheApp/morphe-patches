/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.requests.proxy;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Cronet connection that applies the configured timeouts to asynchronous connection setup,
 * request I/O, response headers, and response body reads.
 */
public final class CronetHttpURLConnection extends DelegatingHttpURLConnection {
    private static final int TIMEOUT_STATE_ACTIVE = 0;
    private static final int TIMEOUT_STATE_COMPLETED = 1;
    private static final int TIMEOUT_STATE_EXPIRED = 2;

    private static final ScheduledThreadPoolExecutor TIMEOUT_EXECUTOR = createTimeoutExecutor();

    private final Consumer<IllegalStateException> engineUnavailableCallback;
    private volatile RequestDeadline connectDeadline;
    private InputStream inputStream;
    private InputStream errorStream;
    private OutputStream outputStream;
    private volatile boolean requestStarted;
    private volatile boolean responseHeadersReceived;
    private int responseCode;

    public CronetHttpURLConnection(
            HttpURLConnection delegate,
            Consumer<IllegalStateException> engineUnavailableCallback
    ) {
        super(delegate);
        this.engineUnavailableCallback = engineUnavailableCallback;
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "Morphe-Network-Timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private RequestDeadline getOrCreateConnectDeadline() {
        RequestDeadline deadline = connectDeadline;
        if (deadline != null) {
            return deadline;
        }

        synchronized (this) {
            if (connectDeadline == null) {
                connectDeadline = RequestDeadline.afterMillis(getConnectTimeout());
            }
            return connectDeadline;
        }
    }

    private RequestDeadline responseDeadline() {
        int connectTimeout = getConnectTimeout();
        int readTimeout = getReadTimeout();

        if (requestStarted) {
            return RequestDeadline.afterMillis(readTimeout);
        }

        // A zero value means that the corresponding phase may wait indefinitely. Cronet does not
        // expose the boundary between connecting and reading response headers, so a finite total
        // deadline is only equivalent to HttpURLConnection when both phases are bounded.
        if (connectTimeout == 0 || readTimeout == 0) {
            return RequestDeadline.none();
        }

        RequestDeadline deadline = connectDeadline;
        return deadline == null
                ? RequestDeadline.afterMillis((long) connectTimeout + readTimeout)
                : deadline.extendByMillis(readTimeout);
    }

    private RequestDeadline requestStartDeadline() {
        return requestStarted
                ? RequestDeadline.afterMillis(getReadTimeout())
                : getOrCreateConnectDeadline();
    }

    private void markRequestStarted() {
        requestStarted = true;
        connectDeadline = null;
    }

    private <T> T executeWithDeadline(IoOperation<T> operation,
                                      RequestDeadline deadline) throws IOException {
        if (!deadline.isFinite()) {
            return operation.execute();
        }

        long remainingNanos = deadline.remainingNanos();
        if (remainingNanos <= 0) {
            disconnectAfterTimeout();
            throw createTimeoutException(null);
        }

        TimeoutGuard guard = new TimeoutGuard(remainingNanos);
        T result;
        try {
            result = operation.execute();
        } catch (IOException | RuntimeException ex) {
            if (guard.complete()) {
                throw createTimeoutException(ex);
            }
            throw ex;
        } catch (Error error) {
            guard.complete();
            throw error;
        }

        if (guard.complete()) {
            throw createTimeoutException(null);
        }
        return result;
    }

    private <T> T executeRequestStartWithDeadline(IoOperation<T> operation,
                                                  RequestDeadline deadline) throws IOException {
        try {
            return executeWithDeadline(operation, deadline);
        } catch (IllegalStateException ex) {
            engineUnavailableCallback.accept(ex);
            throw ex;
        }
    }

    private <T> T executeWithIoTimeout(IoOperation<T> operation) throws IOException {
        return executeWithDeadline(
                operation,
                RequestDeadline.afterMillis(getReadTimeout())
        );
    }

    private void disconnectAfterTimeout() {
        try {
            delegate.disconnect();
        } catch (RuntimeException ignored) {
            // The waiting call still observes the timeout.
        }
    }

    private static SocketTimeoutException createTimeoutException(Throwable cause) {
        SocketTimeoutException exception = new SocketTimeoutException("HTTP request timed out");
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private void ensureResponseHeaders() throws IOException {
        if (responseHeadersReceived) {
            return;
        }

        synchronized (this) {
            if (!responseHeadersReceived) {
                responseCode = executeRequestStartWithDeadline(
                        delegate::getResponseCode,
                        responseDeadline()
                );
                markRequestStarted();
                responseHeadersReceived = true;
            }
        }
    }

    @Override
    public void connect() throws IOException {
        executeRequestStartWithDeadline(() -> {
            super.connect();
            return null;
        }, requestStartDeadline());
        markRequestStarted();
    }

    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
        if (outputStream == null) {
            OutputStream delegateOutputStream = executeRequestStartWithDeadline(
                    super::getOutputStream,
                    requestStartDeadline()
            );
            markRequestStarted();
            outputStream = new WriteTimeoutOutputStream(delegateOutputStream);
        }
        return outputStream;
    }

    @Override
    public synchronized InputStream getInputStream() throws IOException {
        if (inputStream == null) {
            ensureResponseHeaders();
            inputStream = new ReadTimeoutInputStream(
                    executeWithIoTimeout(delegate::getInputStream)
            );
        }
        return inputStream;
    }

    @Override
    public synchronized InputStream getErrorStream() {
        if (errorStream != null) {
            return errorStream;
        }

        try {
            ensureResponseHeaders();
            InputStream delegateErrorStream = delegate.getErrorStream();
            if (delegateErrorStream != null) {
                errorStream = new ReadTimeoutInputStream(delegateErrorStream);
            }
        } catch (IOException ignored) {
            // HttpURLConnection.getErrorStream() cannot report the failure.
        }

        return errorStream;
    }

    @Override
    protected void beforeResponse() throws IOException {
        ensureResponseHeaders();
    }

    @Override
    public int getResponseCode() throws IOException {
        ensureResponseHeaders();
        return responseCode;
    }

    @FunctionalInterface
    private interface IoOperation<T> {
        T execute() throws IOException;
    }

    private final class ReadTimeoutInputStream extends FilterInputStream {
        private ReadTimeoutInputStream(InputStream inputStream) {
            super(inputStream);
        }

        @Override
        public int read() throws IOException {
            return executeWithIoTimeout(super::read);
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return executeWithIoTimeout(() -> super.read(buffer));
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return executeWithIoTimeout(() -> super.read(buffer, offset, length));
        }

        @Override
        public long skip(long byteCount) throws IOException {
            return executeWithIoTimeout(() -> super.skip(byteCount));
        }
    }

    private final class WriteTimeoutOutputStream extends OutputStream {
        private final OutputStream delegateOutputStream;

        private WriteTimeoutOutputStream(OutputStream delegateOutputStream) {
            this.delegateOutputStream = delegateOutputStream;
        }

        // Cronet has no separate write-timeout setting. Once the output stream is acquired, use
        // the configured I/O timeout per operation instead of extending the connect deadline over
        // body preparation and the entire upload.

        @Override
        public void write(int value) throws IOException {
            executeWithIoTimeout(() -> {
                delegateOutputStream.write(value);
                return null;
            });
        }

        @Override
        public void write(byte[] buffer) throws IOException {
            executeWithIoTimeout(() -> {
                delegateOutputStream.write(buffer);
                return null;
            });
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            executeWithIoTimeout(() -> {
                delegateOutputStream.write(buffer, offset, length);
                return null;
            });
        }

        @Override
        public void flush() throws IOException {
            executeWithIoTimeout(() -> {
                delegateOutputStream.flush();
                return null;
            });
        }

        @Override
        public void close() throws IOException {
            executeWithIoTimeout(() -> {
                delegateOutputStream.close();
                return null;
            });
        }
    }

    private static final class RequestDeadline {
        private static final RequestDeadline NONE = new RequestDeadline(0, 0);

        private final long startNanos;
        private final long timeoutNanos;

        private RequestDeadline(long startNanos, long timeoutNanos) {
            this.startNanos = startNanos;
            this.timeoutNanos = timeoutNanos;
        }

        private static RequestDeadline none() {
            return NONE;
        }

        private static RequestDeadline afterMillis(long timeoutMillis) {
            return timeoutMillis <= 0
                    ? NONE
                    : new RequestDeadline(
                            System.nanoTime(),
                            TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                    );
        }

        private boolean isFinite() {
            return timeoutNanos != 0;
        }

        private long remainingNanos() {
            return timeoutNanos - (System.nanoTime() - startNanos);
        }

        private RequestDeadline extendByMillis(long timeoutMillis) {
            return isFinite() && timeoutMillis > 0
                    ? new RequestDeadline(
                            startNanos,
                            timeoutNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                    )
                    : NONE;
        }
    }

    private final class TimeoutGuard {
        private final AtomicInteger state = new AtomicInteger(TIMEOUT_STATE_ACTIVE);
        private final ScheduledFuture<?> timeoutTask;

        private TimeoutGuard(long timeoutNanos) {
            timeoutTask = TIMEOUT_EXECUTOR.schedule(() -> {
                if (state.compareAndSet(TIMEOUT_STATE_ACTIVE, TIMEOUT_STATE_EXPIRED)) {
                    disconnectAfterTimeout();
                }
            }, timeoutNanos, TimeUnit.NANOSECONDS);
        }

        private boolean complete() {
            if (state.compareAndSet(TIMEOUT_STATE_ACTIVE, TIMEOUT_STATE_COMPLETED)) {
                timeoutTask.cancel(false);
                return false;
            }
            return state.get() == TIMEOUT_STATE_EXPIRED;
        }
    }
}
