package io.usebuildflow.kept;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * After the phone restarts, or after Kept is updated: if tracking was on, turn it back on.
 * Android only lets a location service start from the background when the driver chose
 * "Allow all the time"; otherwise Kept shows a notice to tap, and tracking resumes when the app opens.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        String a = i == null ? null : i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!TripStore.state(c).optBoolean("on", false)) return;
        boolean fine = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean background = Build.VERSION.SDK_INT < 29
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine && background) {
            try { TrackingService.command(c, TrackingService.ACTION_START, null, null); return; }
            catch (Exception e) { CrashLog.record(e); }
        }
        TrackingService.notifyStopped(c, Intent.ACTION_BOOT_COMPLETED.equals(a) ? "restart" : "update");
    }
}
