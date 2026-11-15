package io.usebuildflow.kept;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Crash and error reporting through Firebase Crashlytics when it is part of the build
 * (the build adds it only when google-services.json is present). Without it, errors go to the device log.
 * Reflection keeps this file compiling in both kinds of build.
 */
final class CrashLog {
    private static final String TAG = "Kept";
    private CrashLog() {}

    private static Object crashlytics() throws Exception {
        Class<?> k = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics");
        return k.getMethod("getInstance").invoke(null);
    }

    static void record(Throwable t) {
        Log.w(TAG, "error", t);
        try { Object c = crashlytics(); Method m = c.getClass().getMethod("recordException", Throwable.class); m.invoke(c, t); } catch (Throwable ignored) {}
    }

    static void log(String message) {
        Log.i(TAG, message);
        try { Object c = crashlytics(); Method m = c.getClass().getMethod("log", String.class); m.invoke(c, message); } catch (Throwable ignored) {}
    }

    /** An error caught in the web screen (message and stack only; never trip or money data). */
    static void recordWeb(String message, String stack) {
        Exception e = new Exception("web: " + (message == null ? "" : message));
        if (stack != null) log(stack.length() > 1500 ? stack.substring(0, 1500) : stack);
        record(e);
    }
}
