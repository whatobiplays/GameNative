package com.winlator.core

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.winlator.PrefManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Exercises the public GPU getters with seeded preferences. The positive case supplies a
 * driver reading at the EGL reader boundary because the test sandbox cannot run the device probe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = Application::class,
    shadows = [GPUInformationPersistenceTest.SkipNativeLibrary::class],
)
class GPUInformationPersistenceTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** A stored set belonging to some other GPU than anything this build could detect. */
    private fun storedGpuInformation() = mutablePreferencesOf(
        stringPreferencesKey("gpu_renderer2") to "Adreno (TM) 740",
        stringPreferencesKey("gpu_vendor") to "Qualcomm",
        stringPreferencesKey("gpu_version") to "OpenGL ES 3.2 V740",
    )

    /** The three keys that used to hold GPU information, which nothing should consult any more. */
    private val gpuKeys = listOf(
        stringPreferencesKey("gpu_renderer2"),
        stringPreferencesKey("gpu_vendor"),
        stringPreferencesKey("gpu_version"),
    )

    /** The stored renderer must not come back as though it were the detected one. */
    @Test(timeout = 30_000)
    fun storedRendererIsNotReportedAsDetected() {
        val store = RecordingPreferenceStore(storedGpuInformation())
        installWinlatorPreferences(store).use {
            val reported = readOnThisMachine { GPUInformation.getRenderer(context) }

            assertTrue("Stored renderer came back as the GPU: $reported", !reported.contains("740"))
            assertEquals("GPU information was read back from storage.", 0, store.reads.get())
        }
    }

    /** Same for the other two keys, which reach the app through their own getters. */
    @Test(timeout = 30_000)
    fun storedVendorAndVersionAreNotReportedAsDetected() {
        val store = RecordingPreferenceStore(storedGpuInformation())
        installWinlatorPreferences(store).use {
            val vendor = readOnThisMachine { GPUInformation.getVendor(context) }
            val version = readOnThisMachine { GPUInformation.getVersion(context) }

            assertTrue("Stored vendor came back: $vendor", !vendor.contains("Qualcomm"))
            assertTrue("Stored version came back: $version", !version.contains("V740"))
            assertEquals("GPU information was read back from storage.", 0, store.reads.get())
        }
    }

    /**
     * Nothing about the old keys changes; they stop being consulted, and nothing new is written
     * over them. An upgrade from an older install sees the same untouched entries.
     */
    @Test(timeout = 30_000)
    fun legacyKeysAreLeftWhereTheyWere() {
        val store = RecordingPreferenceStore(storedGpuInformation())
        installWinlatorPreferences(store).use {
            readOnThisMachine { GPUInformation.getRenderer(context) }
            readOnThisMachine { GPUInformation.getVendor(context) }
            readOnThisMachine { GPUInformation.getVersion(context) }

            awaitQuietStore(store)
            assertEquals("Something was written to storage.", 0, store.writes.get())
            val stillStored = runBlocking { store.data.first() }
            assertEquals("Adreno (TM) 740", stillStored[stringPreferencesKey("gpu_renderer2")])
            assertEquals("Qualcomm", stillStored[stringPreferencesKey("gpu_vendor")])
            assertEquals("OpenGL ES 3.2 V740", stillStored[stringPreferencesKey("gpu_version")])
        }
    }

    /**
     * Asking the GPU used to park the caller on a notification that a failed probe never sends.
     * With nothing stored there is one attempt per call, the call comes back, and no attempt is
     * remembered as an answer.
     */
    @Test(timeout = 20_000)
    fun missingGpuInformationIsNotStoredAsAnAnswer() {
        val store = RecordingPreferenceStore(mutablePreferencesOf())
        installWinlatorPreferences(store).use {
            assertEquals("", readOnThisMachine { GPUInformation.getRenderer(context) })
            assertEquals("", readOnThisMachine { GPUInformation.getRenderer(context) })

            awaitQuietStore(store)
            assertEquals("GPU information was read back from storage.", 0, store.reads.get())
            assertEquals("A failed lookup was written to storage.", 0, store.writes.get())

            val stillStored = runBlocking { store.data.first() }
            assertTrue("A failed lookup was stored as an answer.", gpuKeys.none { it in stillStored.asMap() })
        }
    }

    /** The stale preference and a complete driver reading disagree. */
    @Test(timeout = 30_000)
    @Config(shadows = [GpuProbe.SuccessfulEglReader::class, PreferenceWriteSubmissions::class])
    fun liveReadingWinsOverTheStoredRenderer() {
        val store = RecordingPreferenceStore(storedGpuInformation())
        installWinlatorPreferences(store).use {
            assertEquals("Adreno (TM) 650", readOnThisMachine { GPUInformation.getRenderer(context) })
            assertEquals("Test GPU vendor", readOnThisMachine { GPUInformation.getVendor(context) })
            assertEquals("OpenGL ES 3.2 V31", readOnThisMachine { GPUInformation.getVersion(context) })
            assertEquals("Adreno (TM) 650", readOnThisMachine { GPUInformation.getRenderer(context) })
            assertEquals("Probe attempts.", 1, GpuProbe.SuccessfulEglReader.attempts.get())
            assertEquals("GPU information was read back from storage.", 0, store.reads.get())
            assertEquals("GPU preference writes were submitted.", 0, PreferenceWriteSubmissions.count.get())
            assertEquals(storedGpuInformation(), runBlocking { store.data.first() })

            PrefManager.putString("gpu_renderer2", "Write submission control")
            assertEquals("The shadow must observe write submissions.", 1, PreferenceWriteSubmissions.count.get())
        }
    }

    /**
     * Shows that the zero-write assertions above are able to fail.
     *
     * Concluding that nothing was written only means something if a write would have been
     * counted. PrefManager dispatches write-back on a background scope, so the control submits
     * one the same way and waits on the future that submission returns rather than on a delay.
     * A recorder that misses the control would equally have missed the real thing.
     */
    @Test(timeout = 30_000)
    fun writeDispatchedOnThePreferenceScopeIsCounted() {
        val store = RecordingPreferenceStore(storedGpuInformation())
        installWinlatorPreferences(store).use {
            readOnThisMachine { GPUInformation.getRenderer(context) }

            val control = PrefManager.putString("gpu_renderer2", "Written By Test Control")
            val outcome = try {
                control.get(10, TimeUnit.SECONDS)
                "written"
            } catch (notWritten: Exception) {
                "not written: " + notWritten
            }

            assertEquals("A dispatched write never reached the store.", "written", outcome)
            assertEquals("A dispatched write was not counted.", 1, store.writes.get())
            val nowStored = runBlocking { store.data.first() }
            assertEquals("Written By Test Control", nowStored[stringPreferencesKey("gpu_renderer2")])
        }
    }

    /**
     * Waits for the preference store to go quiet before a test concludes that nothing was written.
     *
     * Write-back ran on a background scope, so reading the counters the instant the getters return
     * would pass whatever the code did. Quiet means no write has been in flight for [quietMs] in a
     * row. Waiting on that rather than on a fixed delay catches a write that starts late, and the
     * [timeoutMs] cap keeps a stalled store from holding up the test executor.
     */
    private fun awaitQuietStore(store: RecordingPreferenceStore, quietMs: Long = 400, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            if (store.inFlight.get() > 0) {
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= quietMs) {
                return
            }
        }
    }

    /** Bounds the wait because the baseline can miss the probe worker's notification. */
    private fun readOnThisMachine(timeoutMs: Long = 5_000, read: () -> String): String {
        var reported = ""
        var broken: Throwable? = null
        val reader = Thread {
            try {
                reported = read()
            } catch (unexpected: Throwable) {
                broken = unexpected
            }
        }.apply { isDaemon = true }

        reader.start()
        reader.join(timeoutMs)
        assertFalse("GPU information never came back after " + timeoutMs + " ms.", reader.isAlive)

        val failure = broken
        if (failure != null && !isMissingHostGraphicsDependency(failure)) {
            throw AssertionError("GPU information getter failed: " + failure, failure)
        }
        return reported
    }

    /** The sandbox fails to resolve EGLConfig when the real reader allocates its EGL config array. */
    private fun isMissingHostGraphicsDependency(failure: Throwable): Boolean {
        val type = "javax.microedition.khronos.egl.EGLConfig"
        val cause = failure.cause
        return failure is NoClassDefFoundError &&
            failure.message?.replace('/', '.') == type &&
            cause is ClassNotFoundException &&
            cause.message == type
    }

    /** Counts submissions before PrefManager can dispatch asynchronous work. */
    @Implements(value = PrefManager::class, isInAndroidSdk = false)
    class PreferenceWriteSubmissions {
        companion object {
            val count = AtomicInteger(0)

            @JvmStatic
            @Implementation
            fun putString(key: String, value: String): CompletableFuture<Unit> {
                count.incrementAndGet()
                return CompletableFuture.completedFuture(Unit)
            }
        }
    }

    /**
     * [DataStore] that behaves like the real one, including writes actually landing, and counts
     * reads and writes separately so either can be asserted on.
     */
    private class RecordingPreferenceStore(initial: Preferences) : DataStore<Preferences> {

        private val state = MutableStateFlow(initial)
        private val mutex = Mutex()

        val reads = AtomicInteger(0)
        val writes = AtomicInteger(0)

        /** Writes under way right now, which is what a quiet period is measured against. */
        val inFlight = AtomicInteger(0)

        override val data: Flow<Preferences>
            get() {
                reads.incrementAndGet()
                return state
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writes.incrementAndGet()
            inFlight.incrementAndGet()
            try {
                return mutex.withLock {
                    val updated = transform(state.value).toMutablePreferences()
                    state.value = updated
                    updated
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /** Swaps [com.winlator.PrefManager]'s backing store and puts the old one back afterwards. */
    private fun installWinlatorPreferences(store: DataStore<Preferences>): AutoCloseable {
        val manager = Class.forName("com.winlator.PrefManager")
        val instance = manager.getField("INSTANCE").get(null)
        val field = manager.getDeclaredField("dataStore")
        field.isAccessible = true
        val previous = field.get(instance)
        field.set(instance, store)
        return AutoCloseable { field.set(instance, previous) }
    }

    /** Host tests have no libextras; skipping only that load leaves the real cache in place. */
    @Implements(value = GPUInformation::class, isInAndroidSdk = false)
    class SkipNativeLibrary {
        companion object {
            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}
        }
    }
}
