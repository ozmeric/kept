package io.usebuildflow.kept;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Set;

/**
 * Small durable store (SharedPreferences) shared by the tracking service, the notification
 * listener and the Capacitor plugin.
 *  - "trips":   trips recorded natively, waiting for the web app to drain + ack them
 *  - "state":   the live tracking state (mode, order flag, current segment)
 *  - "config":  what the web app told us (apps, unit, notification rules)
 *  - "log":     the last 200 gig-app notifications (learn mode, to tune keywords)
 */
public final class TripStore {
    private static final String PREFS = "kept_native";
    private static final int LOG_MAX = 200;

    private TripStore() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray arr(Context c, String key) {
        try { return new JSONArray(sp(c).getString(key, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    private static JSONObject obj(Context c, String key) {
        try { return new JSONObject(sp(c).getString(key, "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    // ── trips ──
    public static synchronized void addTrip(Context c, JSONObject trip) {
        JSONArray a = arr(c, "trips");
        a.put(trip);
        sp(c).edit().putString("trips", a.toString()).apply();
    }

    public static synchronized JSONArray pendingTrips(Context c) {
        return arr(c, "trips");
    }

    public static synchronized void ack(Context c, Set<String> ids) {
        JSONArray a = arr(c, "trips"), keep = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t != null && !ids.contains(t.optString("id"))) keep.put(t);
        }
        sp(c).edit().putString("trips", keep.toString()).apply();
    }

    // ── live state ──
    public static synchronized JSONObject state(Context c) { return obj(c, "state"); }

    public static synchronized void saveState(Context c, JSONObject s) {
        sp(c).edit().putString("state", s.toString()).apply();
    }

    // ── config from the web app ──
    public static synchronized JSONObject config(Context c) { return obj(c, "config"); }

    public static synchronized void saveConfig(Context c, JSONObject cfg) {
        sp(c).edit().putString("config", cfg.toString()).apply();
    }

    // ── learn-mode notification log ──
    public static synchronized void log(Context c, JSONObject item) {
        JSONArray a = arr(c, "log"), out = new JSONArray();
        int start = Math.max(0, a.length() - (LOG_MAX - 1));
        for (int i = start; i < a.length(); i++) out.put(a.opt(i));
        out.put(item);
        sp(c).edit().putString("log", out.toString()).apply();
    }

    public static synchronized JSONArray log(Context c) { return arr(c, "log"); }
}
