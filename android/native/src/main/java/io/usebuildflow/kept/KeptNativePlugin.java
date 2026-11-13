package io.usebuildflow.kept;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridge used by index.html as window.Capacitor.Plugins.KeptNative.
 * Methods: configure, getStatus, startTracking, stopTracking, setMode, startRide, completeRide,
 *          drainTrips, ackTrips, checkPermissions, openNotificationSettings, getNotificationLog,
 *          setWidget, getSharedImage
 * Events:  status, tripsChanged, rideCompleted, sharedImage
 */
@CapacitorPlugin(
    name = "KeptNative",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class KeptNativePlugin extends Plugin implements TrackingService.Events {

    // an image shared to Kept from another app (offer screenshot), waiting for the web app to pick it up
    private static String sharedUri = null, sharedType = null;
    private static KeptNativePlugin instance = null;

    static void setSharedImage(String uri, String type) {
        sharedUri = uri; sharedType = type;
        if (instance != null) instance.notifyListeners("sharedImage", new JSObject());
    }

    @Override public void load() {
        instance = this;
        TrackingService.setEvents(this);
        TrackingService.createChannels(getContext());
        // resume tracking after the app is reopened (unless the user paused it)
        JSONObject st = TripStore.state(getContext());
        if (st.optBoolean("on", true) && getPermissionState("location") == PermissionState.GRANTED)
            TrackingService.command(getContext(), TrackingService.ACTION_START, null, null);
    }

    @Override protected void handleOnDestroy() { TrackingService.setEvents(null); if (instance == this) instance = null; }

    // ── events from the service (may arrive on any thread) ──
    @Override public void onStatus(JSONObject status) { notifyListeners("status", toJS(status)); }
    @Override public void onTripsChanged() { notifyListeners("tripsChanged", new JSObject()); }
    @Override public void onRideCompleted(String tripId) { JSObject o = new JSObject(); o.put("id", tripId); notifyListeners("rideCompleted", o); }

    private static JSObject toJS(JSONObject o) {
        try { return JSObject.fromJSONObject(o); } catch (JSONException e) { return new JSObject(); }
    }

    // ── methods ──
    @PluginMethod public void configure(PluginCall call) {
        JSONObject cfg = new JSONObject();
        try {
            cfg.put("apps", call.getArray("apps", new JSArray()));
            cfg.put("unit", call.getString("unit", "mi"));
            cfg.put("lang", call.getString("lang", "en"));
            JSObject rules = call.getObject("rules", new JSObject());
            cfg.put("rules", rules);
        } catch (JSONException ignored) {}
        TripStore.saveConfig(getContext(), cfg);
        call.resolve();
    }

    @PluginMethod public void getStatus(PluginCall call) { call.resolve(toJS(TrackingService.status(getContext()))); }

    @PluginMethod public void startTracking(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) { requestAllPermissions(call, "locationGranted"); return; }   // location + notifications
        TrackingService.command(getContext(), TrackingService.ACTION_START, null, null);
        call.resolve();
    }

    @PermissionCallback private void locationGranted(PluginCall call) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            TrackingService.command(getContext(), TrackingService.ACTION_START, null, null);
            call.resolve();
        } else call.reject("Location permission denied");
    }

    @PluginMethod public void stopTracking(PluginCall call) {
        TrackingService.command(getContext(), TrackingService.ACTION_STOP, null, null);
        call.resolve();
    }

    @PluginMethod public void setMode(PluginCall call) {
        TrackingService.command(getContext(), TrackingService.ACTION_MODE, call.getString("mode", TrackingService.PERSONAL), null);
        call.resolve();
    }

    @PluginMethod public void startRide(PluginCall call) {
        TrackingService.command(getContext(), TrackingService.ACTION_RIDE_START, null, "app");
        call.resolve();
    }

    @PluginMethod public void completeRide(PluginCall call) {
        TrackingService.command(getContext(), TrackingService.ACTION_RIDE_DONE, null, "app");
        call.resolve();
    }

    /** Returns trips recorded natively; they stay stored until ackTrips confirms the web app saved them. */
    @PluginMethod public void drainTrips(PluginCall call) {
        JSObject o = new JSObject();
        try { o.put("trips", new JSArray(TripStore.pendingTrips(getContext()).toString())); } catch (JSONException e) { o.put("trips", new JSArray()); }
        call.resolve(o);
    }

    @PluginMethod public void ackTrips(PluginCall call) {
        Set<String> ids = new HashSet<>();
        JSArray a = call.getArray("ids", new JSArray());
        for (int i = 0; i < a.length(); i++) ids.add(a.optString(i));
        TripStore.ack(getContext(), ids);
        call.resolve();
    }

    @PluginMethod public void checkPermissions(PluginCall call) {
        JSObject o = new JSObject();
        o.put("location", getPermissionState("location") == PermissionState.GRANTED);
        o.put("notifications", NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName()));
        call.resolve(o);
    }

    /** Opens Android's "Notification access" screen so the user can allow Kept to read gig-app notifications. */
    @PluginMethod public void openNotificationSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    /** Background location ("Allow all the time") lets rides auto-start while Kept is closed. */
    @PluginMethod public void openAppSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    /** Today's numbers for the home-screen widget (text already formatted in the driver's language and currency). */
    @PluginMethod public void setWidget(PluginCall call) {
        KeptWidget.save(getContext(), call.getString("amount"), call.getString("label"), call.getString("start"), call.getString("done"), call.getString("open"));
        call.resolve();
    }

    /** Returns { data: base64, mime } for an image shared to Kept, once; { data: null } when there is none. */
    @PluginMethod public void getSharedImage(PluginCall call) {
        JSObject o = new JSObject();
        String uri = sharedUri, type = sharedType;
        sharedUri = null; sharedType = null;
        if (uri == null) { o.put("data", (String) null); call.resolve(o); return; }
        try (InputStream in = getContext().getContentResolver().openInputStream(Uri.parse(uri))) {
            if (in == null) throw new Exception("no stream");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384]; int n; long total = 0;
            while ((n = in.read(chunk)) > 0) { total += n; if (total > 15L * 1024 * 1024) throw new Exception("image too large"); buf.write(chunk, 0, n); }
            o.put("data", Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP));
            o.put("mime", type == null ? "image/jpeg" : type);
            call.resolve(o);
        } catch (Exception e) {
            call.reject("Could not read the shared image: " + e.getMessage());
        }
    }

    @PluginMethod public void getNotificationLog(PluginCall call) {
        JSONArray log = TripStore.log(getContext());
        JSObject o = new JSObject();
        try { o.put("items", new JSArray(log.toString())); } catch (JSONException e) { o.put("items", new JSArray()); }
        call.resolve(o);
    }
}
