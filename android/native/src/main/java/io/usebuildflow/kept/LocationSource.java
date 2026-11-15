package io.usebuildflow.kept;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Where location fixes come from. Google's fused provider when Play services is on the phone
 * (GPS + Wi-Fi + sensors, better in cities and easier on the battery); the plain GPS chip otherwise.
 * Fixes about every 3 seconds while moving, at least 5 m apart.
 */
final class LocationSource {
    interface Sink {
        void onFix(Location loc);
        /** The phone's location switch was turned on or off. */
        void onAvailable(boolean available);
        /** No fix right now (indoors, tunnel); still trying. */
        void onSearching();
    }

    private final Context ctx;
    private final Sink sink;
    private FusedLocationProviderClient fused;
    private LocationCallback fusedCb;
    private LocationManager lm;
    private LocationListener gpsListener;
    private String kind = "none";

    LocationSource(Context ctx, Sink sink) { this.ctx = ctx.getApplicationContext(); this.sink = sink; }

    /** "fused", "gps" or "none". */
    String kind() { return kind; }

    /** Starts updates; throws SecurityException when the location permission is missing. */
    void start() {
        stop();
        boolean play = false;
        try { play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS; } catch (Throwable ignored) {}
        if (play) {
            fused = LocationServices.getFusedLocationProviderClient(ctx);
            fusedCb = new LocationCallback() {
                @Override public void onLocationResult(LocationResult r) {
                    if (r == null) return;
                    for (Location l : r.getLocations()) if (l != null) sink.onFix(l);
                }
                @Override public void onLocationAvailability(LocationAvailability a) {
                    if (a != null && !a.isLocationAvailable()) sink.onSearching();
                }
            };
            LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
                    .setMinUpdateIntervalMillis(2000L)
                    .setMinUpdateDistanceMeters(5f)
                    .setWaitForAccurateLocation(false)
                    .build();
            fused.requestLocationUpdates(req, fusedCb, Looper.getMainLooper());
            kind = "fused";
            return;
        }
        lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        gpsListener = new LocationListener() {
            @Override public void onLocationChanged(Location l) { if (l != null) sink.onFix(l); }
            @Override public void onStatusChanged(String p, int st, Bundle b) {}
            @Override public void onProviderEnabled(String p) { sink.onAvailable(true); }
            @Override public void onProviderDisabled(String p) { sink.onAvailable(false); }
        };
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, gpsListener, Looper.getMainLooper());
        kind = "gps";
    }

    void stop() {
        try { if (fused != null && fusedCb != null) fused.removeLocationUpdates(fusedCb); } catch (Exception ignored) {}
        try { if (lm != null && gpsListener != null) lm.removeUpdates(gpsListener); } catch (Exception ignored) {}
        fused = null; fusedCb = null; lm = null; gpsListener = null; kind = "none";
    }
}
