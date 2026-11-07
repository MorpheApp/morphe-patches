package app.morphe.extension.reddit.patches.archive;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Patched into a host SuspendLambda; no second Kotlin runtime is packaged. */
public final class ArchiveActionContinuation {
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Object result;

    public Object invokeSuspend(Object value) {
        result = value; done.countDown();
        // The host BaseContinuationImpl stops here, without forwarding to a null parent.
        return NativeArchiveActions.suspended();
    }
    Object await(Object immediate) throws IOException, InterruptedException {
        if (immediate != NativeArchiveActions.suspended()) return immediate;
        if (!done.await(25, TimeUnit.SECONDS)) throw new IOException("Reddit did not respond in time");
        return result;
    }
}
