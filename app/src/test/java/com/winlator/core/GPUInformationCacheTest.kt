package com.winlator.core

import android.app.Application
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Covers the process-lifetime GPU cache: what it keeps, what it re-reads, and what happens when
 * several threads ask at once.
 *
 * Every test builds its own cache over its own [GpuProbe], so nothing is shared between tests and
 * no test depends on the order they run in. The cache logic under test is the one the app uses;
 * only where the reading comes from is swapped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    shadows = [GPUInformationCacheTest.SkipNativeLibrary::class],
)
class GPUInformationCacheTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** A reading with all three fields, which is the only kind worth caching. */
    private fun fullReading() = GPUInformation.Identity("Adreno (TM) 650", "Qualcomm", "OpenGL ES 3.2 V31")

    /**
     * What the cache stores, it stops asking for: five reads over the same cache cost one probe,
     * and every read sees the same reading.
     */
    @Test
    fun successfulProbeIsNotRepeated() {
        val probe = GpuProbe { fullReading() }
        val cache = GPUInformation.GpuInformationCache(probe)

        repeat(5) {
            val reading = cache.read(context)
            assertEquals("Adreno (TM) 650", reading.renderer)
            assertEquals("Qualcomm", reading.vendor)
            assertEquals("OpenGL ES 3.2 V31", reading.version)
        }

        assertEquals(1, probe.attempts())
    }

    /**
     * A renderer on its own is not an identity. It goes to whoever asked for it but is not kept,
     * so the next call gets another chance at whichever fields were missing.
     */
    @Test
    fun partialProbeIsHandedOverButNotCached() {
        val rendererOnly = GpuProbe { GPUInformation.Identity("Adreno (TM) 650", "", "") }
        val rendererOnlyCache = GPUInformation.GpuInformationCache(rendererOnly)
        assertEquals("Adreno (TM) 650", rendererOnlyCache.read(context).renderer)
        assertEquals("Adreno (TM) 650", rendererOnlyCache.read(context).renderer)
        assertEquals(2, rendererOnly.attempts())

        val missingVersion = GpuProbe { GPUInformation.Identity("Adreno (TM) 650", "Qualcomm", "") }
        val missingVersionCache = GPUInformation.GpuInformationCache(missingVersion)
        assertEquals("", missingVersionCache.read(context).version)
        assertEquals("", missingVersionCache.read(context).version)
        assertEquals(2, missingVersion.attempts())
    }

    /**
     * A driver that answers nothing must not turn one getter into a retry loop: each call spends
     * exactly one attempt and returns without waiting on a second.
     */
    @Test(timeout = 30_000)
    fun emptyProbeRunsOneAttemptPerCall() {
        val probe = GpuProbe { GPUInformation.Identity("", "", "") }
        val cache = GPUInformation.GpuInformationCache(probe)

        repeat(3) { assertEquals("", cache.read(context).renderer) }

        assertEquals(3, probe.attempts())
    }

    /** A probe that throws is a missed reading: logged, empty, and tried again next time. */
    @Test
    fun failedProbeLeavesTheNextCallToSucceed() {
        val probe = GpuProbe { attempt ->
            if (attempt == 1) throw IllegalStateException("no current EGL context") else fullReading()
        }
        val cache = GPUInformation.GpuInformationCache(probe)

        assertEquals("", cache.read(context).renderer)
        assertEquals("Adreno (TM) 650", cache.read(context).renderer)
        assertEquals(2, probe.attempts())
    }

    /**
     * A broken graphics subsystem stays visible instead of reading as "no GPU", which would pick
     * a driver from a missing answer. The failed attempt is not cached, so the next call retries.
     */
    @Test
    fun fatalProbeErrorReachesTheCallerAndIsNotCached() {
        val probe = GpuProbe { attempt ->
            if (attempt == 1) throw LinkageError("graphics classes did not load") else fullReading()
        }
        val cache = GPUInformation.GpuInformationCache(probe)

        try {
            cache.read(context)
            fail("A fatal error must not look like an empty reading.")
        } catch (expected: LinkageError) {
            // Recorded by the throw itself.
        }

        assertEquals("Adreno (TM) 650", cache.read(context).renderer)
        assertEquals(2, probe.attempts())
    }

    /**
     * Threads that arrive while a probe is already running join it instead of starting their own,
     * and all of them get the reading that probe produced.
     */
    @Test(timeout = 30_000)
    fun concurrentFirstCallersShareOneProbe() {
        val releaseProbe = CountDownLatch(1)
        val probeStarted = CountDownLatch(1)
        val probe = GpuProbe {
            probeStarted.countDown()
            assertTrue("The probe was never released by the test.", releaseProbe.await(10, TimeUnit.SECONDS))
            fullReading()
        }
        val cache = GPUInformation.GpuInformationCache(probe)
        val log = WorkerLog()

        val callers = (1..8).map { startWorker({ cache.read(context).renderer }, log) }
        assertTrue("No probe started.", probeStarted.await(10, TimeUnit.SECONDS))
        assertTrue("Callers never queued up behind the probe.", awaitWaiters(callers, atLeast = 4))
        releaseProbe.countDown()
        joinAll(callers)

        assertEquals(1, probe.attempts())
        assertEquals(1, probe.highestConcurrentProbes())
        assertNoWorkerFailures(log)
        assertEquals(8, log.results.size)
        assertTrue("Callers disagree about the GPU: " + log.results, log.results.all { it == "Adreno (TM) 650" })
    }

    /**
     * One thread giving up must not cost the others their reading. The interrupt status is put
     * back, the shared probe keeps running, and no second probe starts.
     */
    @Test(timeout = 30_000)
    fun interruptingOneWaiterLeavesTheSharedProbeAlone() {
        val releaseProbe = CountDownLatch(1)
        val probeStarted = CountDownLatch(1)
        val probe = GpuProbe {
            probeStarted.countDown()
            assertTrue("The probe was never released by the test.", releaseProbe.await(10, TimeUnit.SECONDS))
            fullReading()
        }
        val cache = GPUInformation.GpuInformationCache(probe)
        val interruptRestored = mutableListOf<Boolean>()
        val log = WorkerLog()

        val initiator = startWorker({
            val reading = cache.read(context).renderer
            interruptRestored.add(Thread.currentThread().isInterrupted())
            reading
        }, log)
        assertTrue("No probe started.", probeStarted.await(10, TimeUnit.SECONDS))
        assertTrue("The initiator never reached its wait.", awaitParked(initiator))

        val joiner = startWorker({ cache.read(context).renderer }, log)
        assertTrue("The second caller never reached its wait.", awaitParked(joiner))

        initiator.interrupt()

        // The interrupted caller has to come back while the probe is still running, otherwise the
        // probe may simply answer it and the interrupt is never what ends the wait.
        assertTrue("The interrupted caller never returned.", finishedWithin(initiator, 10_000))
        releaseProbe.countDown()
        joinAll(listOf(initiator, joiner))

        assertNoWorkerFailures(log)
        assertEquals("The interrupted caller keeps its interrupt status.", listOf(true), interruptRestored)
        assertEquals(listOf("", "Adreno (TM) 650"), log.results.sorted())
        assertEquals("One probe served both callers.", 1, probe.attempts())
        assertEquals(1, probe.highestConcurrentProbes())
    }

    /**
     * A probe that finishes with nobody left waiting still publishes its reading, so the next
     * call reuses it rather than starting over.
     */
    @Test(timeout = 60_000)
    fun probePublishesEvenAfterEveryWaiterIsInterrupted() {
        val releaseProbe = CountDownLatch(1)
        val probeStarted = CountDownLatch(1)
        val probe = GpuProbe {
            probeStarted.countDown()
            assertTrue("The probe was never released by the test.", releaseProbe.await(10, TimeUnit.SECONDS))
            fullReading()
        }
        val cache = GPUInformation.GpuInformationCache(probe)
        val log = WorkerLog()

        val onlyCaller = startWorker({ cache.read(context).renderer }, log)
        assertTrue("No probe started.", probeStarted.await(10, TimeUnit.SECONDS))
        assertTrue("The only caller never reached its wait.", awaitParked(onlyCaller))

        onlyCaller.interrupt()
        joinAll(listOf(onlyCaller))

        // The single reading has to be empty, meaning it came back through the interrupt and never
        // saw the probe finish. A reading here would mean the probe won the race and publication
        // went untested.
        assertEquals("The only caller saw the probe finish.", listOf(""), log.results)

        // The probe has to be entirely over before asking again, otherwise the next read could
        // still be joining this probe and would prove nothing about publication.
        releaseProbe.countDown()
        assertTrue("The probe was still running when the next read began.", probe.awaitProbeFinished(10_000))

        assertEquals("Adreno (TM) 650", cache.read(context).renderer)
        assertEquals("A second probe started, so nothing was published.", 1, probe.attempts())
    }

    /**
     * Finishing a probe and making its reading visible happen in one step, so callers that keep
     * asking never slip in between and start a redundant probe.
     */
    @Test(timeout = 60_000)
    fun callersKeepReadingWithoutStartingASecondProbe() {
        val releaseProbe = CountDownLatch(1)
        val probeStarted = CountDownLatch(1)
        val probe = GpuProbe {
            probeStarted.countDown()
            assertTrue("The probe was never released by the test.", releaseProbe.await(10, TimeUnit.SECONDS))
            fullReading()
        }
        val cache = GPUInformation.GpuInformationCache(probe)
        val log = WorkerLog()

        val readers = (1..4).map {
            startWorker({
                // Counted per thread, so a thread started late still reads rather than returning
                // an unused placeholder.
                var last = ""
                var readsLeft = 2_000
                while (readsLeft-- > 0 && probe.attempts() <= 1) {
                    last = cache.read(context).renderer
                }
                last
            }, log)
        }

        assertTrue("No probe started.", probeStarted.await(10, TimeUnit.SECONDS))
        assertTrue("Readers never queued up behind the probe.", awaitWaiters(readers, atLeast = 4))
        releaseProbe.countDown()
        joinAll(readers, timeoutMs = 30_000)
        assertTrue("The probe was still running at the last read.", probe.awaitProbeFinished(10_000))

        assertEquals("A second probe started.", 1, probe.attempts())
        assertEquals(1, probe.highestConcurrentProbes())
        assertNoWorkerFailures(log)
        assertEquals(4, log.results.size)
        assertTrue("Readers disagree about the GPU: " + log.results, log.results.all { it == "Adreno (TM) 650" })
    }

    /** True once [thread] stops running, which for these tests means it is waiting on a probe. */
    private fun awaitParked(thread: Thread, timeoutMs: Long = 5_000): Boolean =
        awaitWaiters(listOf(thread), atLeast = 1, timeoutMs = timeoutMs)

    /** True once [thread] has finished within its own time limit. */
    private fun finishedWithin(thread: Thread, timeoutMs: Long): Boolean {
        thread.join(timeoutMs)
        return !thread.isAlive
    }

    /** True once at least [atLeast] of [threads] are blocked rather than running. */
    private fun awaitWaiters(threads: List<Thread>, atLeast: Int, timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val waiting = threads.count { it.state == Thread.State.WAITING || it.state == Thread.State.TIMED_WAITING }
            if (waiting >= atLeast) return true
            if (threads.none { it.isAlive }) return false
            Thread.sleep(5)
        }
        return false
    }

    /** Waits for every worker, so a probe that never finishes fails the test instead of hanging. */
    private fun joinAll(threads: List<Thread>, timeoutMs: Long = 20_000) {
        threads.forEach { it.join(timeoutMs) }
        assertTrue("A worker thread never finished.", threads.none { it.isAlive })
    }

    /** What each worker thread saw, so a failure inside a thread still fails the test. */
    private class WorkerLog {
        val results = java.util.Collections.synchronizedList(mutableListOf<String>())
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    }

    private fun assertNoWorkerFailures(log: WorkerLog) {
        assertTrue("A worker thread failed: " + log.failures, log.failures.isEmpty())
    }

    /** Runs [action] on a plain thread and records its result or the exception it threw. */
    private fun startWorker(action: () -> String, log: WorkerLog): Thread {
        return Thread {
            try {
                log.results.add(action())
            } catch (failure: Throwable) {
                log.failures.add(failure)
            }
        }.also { it.start() }
    }

    /** Host tests have no libextras, and the cache must not be assembled by hand in tests. */
    @Implements(value = GPUInformation::class, isInAndroidSdk = false)
    class SkipNativeLibrary {
        companion object {
            /** Skip only the native library load; the cache itself runs for real. */
            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}
        }
    }
}
