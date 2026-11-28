package com.winlator.core;

import android.content.Context;
import android.opengl.EGL14;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.winlator.PrefManager;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

/**
 * Reports which GPU this device has.
 *
 * <p>Identity is read from a throwaway EGL context and kept for the life of the process. The
 * three strings come from one read, since a renderer means little on its own: driver and shader
 * choice depends on the vendor and driver version beside it. A read that comes back empty or
 * partial is handed to the caller and dropped, so the next call tries again. A read that fills
 * all three is kept and never repeated.
 *
 * <p>Nothing here survives a process restart. An earlier build cached the identity in
 * preferences and read it back before probing, which let one wrong or half-finished result
 * decide driver and shader selection for as long as the app stayed installed. Nothing
 * rechecked it, and the stored value could outlive the driver build it came from.
 *
 * <p>Reading blocks until the probe finishes, on whichever thread asked for it. A driver call
 * that never returns blocks the caller for as long as it runs. There is no timeout: a timeout
 * would report an unknown GPU and then pick a driver from that.
 */
public abstract class GPUInformation {

    private static final String TAG = "GPUInformation";

    static {
        System.loadLibrary("extras");
    }

    /** Finds a renderer name in gpu_cards.json, by exact name or by device id. */
    private static JSONObject findCard(Context context, String gpuName) {
        if (gpuName == null || gpuName.isEmpty()) return null;
        try {
            JSONArray jsonArray = new JSONArray(FileUtils.readString(context, "gpu_cards.json"));
            JSONObject partial = null;
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jobj = jsonArray.getJSONObject(i);
                String name = jobj.getString("name");
                if (name.equals(gpuName) || jobj.getString("deviceID").equals(gpuName)) return jobj;
                if (name.contains(gpuName)) partial = jobj;
            }
            return partial;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public static String getCardNameFromGPUName(Context context, String gpuName) {
        JSONObject card = findCard(context, gpuName);
        return card != null ? card.optString("name", gpuName) : gpuName;
    }

    public static String getDeviceIdFromGPUName(Context context, String gpuName) {
        JSONObject card = findCard(context, gpuName);
        return card != null ? card.optString("deviceID", "") : "";
    }

    public static String getVendorIdFromGPUName(Context context, String gpuName) {
        JSONObject card = findCard(context, gpuName);
        return card != null ? card.optString("vendorID", "") : "";
    }

    /**
     * One reading of the graphics driver: renderer, vendor and version, as reported.
     *
     * <p>A reading counts only when all three fields arrived. A renderer by itself leaves the
     * vendor and driver version to be guessed, and keeping a guess is how a stale identity gets
     * to sit in place for months.
     */
    static final class Identity {

        static final Identity UNAVAILABLE = new Identity("", "", "");

        final String renderer;
        final String vendor;
        final String version;

        Identity(String renderer, String vendor, String version) {
            this.renderer = Objects.toString(renderer, "");
            this.vendor = Objects.toString(vendor, "");
            this.version = Objects.toString(version, "");
        }

        /** True when the driver answered all three, which is the only thing worth caching. */
        boolean isComplete() {
            return !renderer.isEmpty() && !vendor.isEmpty() && !version.isEmpty();
        }
    }

    /** Where a reading comes from. The device uses EGL; tests supply a fixed reading. */
    interface GpuReader {

        Identity read(Context context);
    }

    /**
     * Caches one GPU reading for the process and keeps at most one probe running.
     *
     * <p>Every entry point either returns a cached reading, joins the probe already running, or
     * starts one probe and waits for it. No caller retries inside a single call, so a driver that
     * answers nothing costs one probe per call instead of an unbounded loop.
     *
     * <p>A finished probe is replaced by the next call, which keeps a failed read retryable, and
     * the slot is cleared only where a probe could not be started at all. Interrupted callers
     * give up their own wait without cancelling the probe, so one interrupted thread cannot cost
     * the others their reading.
     */
    static final class GpuInformationCache {

        private final GpuReader reader;
        private final Object probeLock = new Object();
        private volatile Identity published;
        private FutureTask<Identity> probe;

        GpuInformationCache(GpuReader reader) {
            this.reader = reader;
        }

        /** Never null. Empty when nothing could be read this time. */
        Identity read(Context context) {
            Identity cached = this.published;
            if (cached != null) {
                return cached;
            }

            FutureTask<Identity> attempt;
            synchronized (this.probeLock) {
                cached = this.published;
                if (cached != null) {
                    return cached;
                }

                FutureTask<Identity> running = this.probe;
                if (running != null && !running.isDone()) {
                    attempt = running;
                } else {
                    FutureTask<Identity> next = new FutureTask<>(() -> probeAndCache(context));
                    this.probe = next;
                    if (!startProbe(next)) {
                        return Identity.UNAVAILABLE;
                    }
                    attempt = next;
                }
            }

            try {
                return attempt.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while reading GPU identity");
                return Identity.UNAVAILABLE;
            } catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                return Identity.UNAVAILABLE;
            }
        }

        /** False, or a rethrown Error, when no probe could run. Never leaves a dead probe in the slot. */
        private boolean startProbe(FutureTask<Identity> probe) {
            try {
                new Thread(probe, "gpu-information").start();
                return true;
            } catch (RuntimeException | Error startupFailure) {
                this.probe = null;
                Log.e(TAG, "GPU probe could not start: " + startupFailure);
                if (startupFailure instanceof Error) {
                    throw startupFailure;
                }
                return false;
            }
        }

        /** Runs on the probe thread. Publishes before returning, so a finished probe is a published one. */
        private Identity probeAndCache(Context context) {
            Identity detected;
            try {
                Identity read = this.reader.read(context);
                detected = read == null ? Identity.UNAVAILABLE : read;
            } catch (RuntimeException failure) {
                Log.w(TAG, "GPU probe failed: " + failure);
                return Identity.UNAVAILABLE;
            } catch (Error failure) {
                Log.e(TAG, "GPU probe raised a fatal error: " + failure);
                throw failure;
            }

            if (detected.isComplete()) {
                synchronized (this.probeLock) {
                    if (this.published == null) {
                        this.published = detected;
                    }
                }
            }
            return detected;
        }
    }

    /**
     * Reads GPU identity through a temporary GLES2 context. The attribute list, context
     * setup and the three glGetString calls are unchanged from before caching was removed,
     * so what a device reports stays the same; only how often it is asked has changed.
     */
    private static final class EglReader implements GpuReader {

        @Override
        public Identity read(Context context) {
            int[] attribList = new int[]{
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 0,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCounts = new int[1];

            EGL10 egl = (EGL10) EGLContext.getEGL();
            EGLDisplay eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

            int[] version = new int[2];
            egl.eglInitialize(eglDisplay, version);
            egl.eglChooseConfig(eglDisplay, attribList, configs, 1, configCounts);

            attribList = new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE};
            EGLContext eglContext = egl.eglCreateContext(eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, attribList);

            egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, eglContext);

            GL10 gl = (GL10) eglContext.getGL();
            return new Identity(
                    Objects.toString(gl.glGetString(GL10.GL_RENDERER), ""),
                    Objects.toString(gl.glGetString(GL10.GL_VENDOR), ""),
                    Objects.toString(gl.glGetString(GL10.GL_VERSION), ""));
        }
    }

    /**
     * Holds the process-wide cache. Kept in its own class so that the only work left in
     * this class's static initializer is loading the native library: host tests skip that
     * load, and the cache still has to come up normally when they do.
     */
    private static final class Default {

        static final GpuInformationCache CACHE = new GpuInformationCache(new EglReader());
    }

    public static String getRenderer(Context context) {
        openPreferenceStore(context);
        return Default.CACHE.read(context).renderer;
    }

    public static String getVendor(Context context) {
        openPreferenceStore(context);
        return Default.CACHE.read(context).vendor;
    }

    public static String getVersion(Context context) {
        openPreferenceStore(context);
        return Default.CACHE.read(context).version;
    }

    /**
     * Opens the Winlator preference store on the way past.
     *
     * <p>Several screens and launcher components read that store without opening it first, and
     * they have always arrived here already having been opened, either by one of their own calls
     * or by an earlier GPU read. Nothing below looks at a preference: GPU identity is cached in
     * memory. The call stays so that those other readers see the store in the state they expect.
     */
    private static void openPreferenceStore(Context context) {
        if (context != null) PrefManager.init(context);
    }

    public static boolean isAdreno6xx(Context context) {
        return getRenderer(context).toLowerCase(Locale.ENGLISH).matches(".*adreno[^6]+6[0-9]{2}.*");
    }

    public static boolean isAdreno8Elite(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        // “adreno … 83x / 84x / 85x”
        return r.contains("adreno") && r.matches(".*\\b8(3[0-9]|4[0-9]|5[0-9])\\b.*");
    }

    public static boolean isAdreno8EliteGen5(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        return r.contains("adreno") && r.matches(".*\\b8(4[0-9]|5[0-9])\\b.*");
    }

    /** Adreno 85x (Snapdragon 8 Elite Gen 6). Also matched by {@link #isAdreno8EliteGen5}. */
    public static boolean isAdreno85x(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        return r.contains("adreno") && r.matches(".*\\b85[0-9]\\b.*");
    }

    /**
     * Detects the Adreno A12 family (e.g. renderer "Adreno (TM) A12").
     * A12 is a distinct family from the numeric 8-Elite parts (84x/85x), so its
     * alphanumeric model token cannot collide with the numeric family regexes.
     */
    public static boolean isAdrenoA12(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        return r.contains("adreno") && r.matches(".*\\ba12\\b.*");
    }

    public static boolean isTurnipCapable(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        // match “adreno 610…699” or “adreno 710…799”
        return r.contains("adreno") && r.matches(".*\\b[67][0-9]{2}\\b.*");
    }

    /**
     * Detects Adreno 710-, 720-, or 732-class GPUs.
     *
     * @return true if the renderer string contains “adreno” and the exact model
     *         number 710, or 720; false otherwise.
     */
    public static boolean isAdreno710_720_732(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        return r.contains("adreno") && r.matches(".*\\b(710|720|732)\\b.*");
    }

    public static boolean isAdreno740(Context context) {
        String r = getRenderer(context).toLowerCase(Locale.ENGLISH);
        return r.contains("adreno") && r.matches(".*\\b740\\b.*");
    }

    public static boolean isAdrenoGPU(Context context) {
        return getRenderer(null, context).toLowerCase().contains("adreno");
    }

    public static boolean isDriverSupported(String driverName, Context context) {
        if (!isAdrenoGPU(context) && !driverName.equals("System"))
            return false;

        String renderer = getRenderer(driverName, context);

        return !renderer.toLowerCase().contains("unknown");
    }

    // This method appears to crash on several devices
    public native static String getVulkanVersion(String driverName, Context context);
    public native static String getRenderer(String driverName, Context context);
    public native static String[] enumerateExtensions(String driverName, Context context);
    public native static int getVendorID(String driverName, Context context);
}

