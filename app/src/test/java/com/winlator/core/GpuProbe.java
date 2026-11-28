package com.winlator.core;

import android.content.Context;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * Stand-in for the EGL probe, so cache behaviour can be observed without a GPU.
 *
 * <p>Every probe is counted, and the highest number of probes running at once with it, which is
 * how a test tells "one probe shared by several callers" apart from "one probe per caller".
 * Written in Java because the reading type it produces is only visible inside this package.
 */
public class GpuProbe implements GPUInformation.GpuReader {

    /** Supplies a complete reading at the EGL boundary for the public getter test. */
    @Implements(className = "com.winlator.core.GPUInformation$EglReader", isInAndroidSdk = false)
    public static class SuccessfulEglReader {
        static final AtomicInteger attempts = new AtomicInteger();

        @Implementation
        protected GPUInformation.Identity read(Context context) {
            attempts.incrementAndGet();
            return new GPUInformation.Identity("Adreno (TM) 650", "Test GPU vendor", "OpenGL ES 3.2 V31");
        }
    }

    /** Produces the reading for one probe, given the 1-based number of that probe. */
    public interface Response {

        GPUInformation.Identity reading(int attempt);
    }

    private final Response response;

    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger highestInFlight = new AtomicInteger();

    /** The thread running the most recent probe, so a test can wait for that probe to be over. */
    private final AtomicReference<Thread> probeThread = new AtomicReference<>();

    public GpuProbe(Response response) {
        this.response = response;
    }

    @Override
    public GPUInformation.Identity read(Context context) {
        probeThread.set(Thread.currentThread());
        int attempt = attempts.incrementAndGet();
        int concurrently = inFlight.incrementAndGet();
        highestInFlight.accumulateAndGet(concurrently, Math::max);
        try {
            return response.reading(attempt);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** How many probes have started so far. */
    public int attempts() {
        return attempts.get();
    }

    /** How many probes were running at the same time, at the most. */
    public int highestConcurrentProbes() {
        return highestInFlight.get();
    }

    /**
     * Waits for the probe that is running, or just finished, to be completely over, meaning the
     * probe thread is no longer running.
     *
     * @return true only if that probe thread has finished. A timed join returns whether or not
     *         the thread stopped, and a caller that reads the GPU while it is still running could
     *         still be joining this same probe. Callers have to check this and fail if it is
     *         false, otherwise a slow machine turns the test into a pass for the wrong reason.
     */
    public boolean awaitProbeFinished(long timeoutMs) throws InterruptedException {
        Thread thread = probeThread.get();
        if (thread != null) {
            thread.join(timeoutMs);
            return !thread.isAlive();
        }
        return true;
    }
}
