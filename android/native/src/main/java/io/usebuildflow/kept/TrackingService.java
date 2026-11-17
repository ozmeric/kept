package io.usebuildflow.kept;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Always-on mileage tracker (foreground service, type "location").
 * Same rules as the web tracker in index.html:
 *  - every movement is added to the current segment (Personal, online for an app, or on an order)
 *  - a segment is saved as a trip when the mode changes, a ride starts/ends, or after 10 min without movement
 *  - fixes worse than 50 m, jitter under 8 m and jumps faster than 70 m/s are ignored
 */
public class TrackingService extends Service implements LocationSource.Sink {
    public static final String ACTION_START = "io.usebuildflow.kept.START";
    public static final String ACTION_STOP = "io.usebuildflow.kept.STOP";
    public static final String ACTION_MODE = "io.usebuildflow.kept.MODE";
    public static final String ACTION_RIDE_START = "io.usebuildflow.kept.RIDE_START";
    public static final String ACTION_RIDE_DONE = "io.usebuildflow.kept.RIDE_DONE";
    public static final String ACTION_RIDE_CANCEL = "io.usebuildflow.kept.RIDE_CANCEL";
    public static final String ACTION_CO_START = "io.usebuildflow.kept.CO_START";   // one-tap company drive
    public static final String ACTION_CO_DONE = "io.usebuildflow.kept.CO_DONE";
    public static final String ACTION_CO_MARK = "io.usebuildflow.kept.CO_MARK";     // "yes" on the reminder; source = segment start (ms)
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SOURCE = "source";
    public static final String PERSONAL = "personal";

    static final String CH_TRACK = "kept_tracking";
    static final String CH_RIDES = "kept_rides";
    static final int NOTIF_TRACK = 4101;
    static final int NOTIF_RIDE = 4102;
    static final String CH_ALERT = "kept_alerts";
    static final int NOTIF_STOPPED = 4103;
    static final int NOTIF_COMPANY = 4104;
    private static final double ASK_KM = 0.4;                         // ask "company drive?" once a drive is clearly under way
    private static final long CO_AUTO_END_MS = 4 * 60 * 60 * 1000L;   // a forgotten company drive ends after 4 h without movement
    private static final long PERSIST_MS = 15_000L;      // state is written to storage at most this often between events
    /** True while the service is alive in this process (the web app compares it with the saved "on" state). */
    static volatile boolean running = false;
    private static volatile JSONObject live;             // newest state, for status() between writes
    private static final long IDLE_MS = 10 * 60 * 1000L;

    /** The plugin registers here to push status / trip events to the web app while it is open. */
    public interface Events {
        void onStatus(JSONObject status);
        void onTripsChanged();
        void onRideCompleted(String tripId);
    }
    private static Events events;
    public static void setEvents(Events e) { events = e; }

    private LocationSource source;
    private JSONObject s;            // live state; written to TripStore on every event and every 15 s while driving
    private long lastPersist = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            checkIdle(System.currentTimeMillis());
            handler.postDelayed(this, 60_000);
        }
    };

    // ── helpers to send commands from the plugin / receiver / listener ──
    public static void command(Context c, String action, String mode, String source) {
        Intent i = new Intent(c, TrackingService.class).setAction(action);
        if (mode != null) i.putExtra(EXTRA_MODE, mode);
        if (source != null) i.putExtra(EXTRA_SOURCE, source);
        try { ContextCompat.startForegroundService(c, i); }
        catch (RuntimeException e) {                 // Android refused a background start (Android 12+)
            CrashLog.record(e);
            if (!ACTION_STOP.equals(action)) notifyStopped(c, "blocked");
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        source = new LocationSource(this, this);
        s = TripStore.state(this);
        live = s; running = true;
        if (!s.has("mode")) put("mode", PERSONAL);
        createChannels(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null && intent.getAction() != null ? intent.getAction() : ACTION_START;
        long now = System.currentTimeMillis();
        boolean fg = startForegroundNow();
        if (!fg && !ACTION_STOP.equals(action)) {    // no location permission, or Android blocked the start
            if (s.optBoolean("on")) notifyStopped(this, "blocked");
            closeSegment(now, null);                  // keep what was driven so far
            restart(now);
            synchronized (s) { TripStore.saveState(this, s); }
            stopUpdates();
            s = new JSONObject();                     // onDestroy must not report this as a second stop
            stopSelf();
            return START_NOT_STICKY;
        }
        if (fg) cancelStopped(this);
        switch (action) {
            case ACTION_STOP:
                closeSegment(now, null);
                s.remove("company"); s.remove("companySeg");
                put("on", false); put("order", false);
                save();
                stopUpdates();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            case ACTION_MODE: {
                String m = intent.getStringExtra(EXTRA_MODE);
                if (m == null) m = PERSONAL;
                if (!m.equals(s.optString("mode")) || s.optBoolean("order")) {
                    closeSegment(now, null);
                    s.remove("company");
                    restart(now); put("mode", m); put("order", false);
                }
                break;
            }
            case ACTION_CO_START:     // company drive: personal-car miles for a company, from now until CO_DONE
                closeSegment(now, null);
                restart(now); put("mode", PERSONAL); put("order", false); s.remove("orderStart");
                put("company", true);
                cancelCompanyAsk(this);
                break;
            case ACTION_CO_DONE:
                if (s.optBoolean("company") || s.optBoolean("companySeg")) {
                    closeSegment(now, null);
                    s.remove("company");
                    restart(now);
                }
                break;
            case ACTION_CO_MARK: {    // the driver answered "yes" on the reminder
                long seg = 0;
                try { seg = Long.parseLong(String.valueOf(intent.getStringExtra(EXTRA_SOURCE))); } catch (NumberFormatException ignored) {}
                if (seg > 0 && seg == s.optLong("segStart") && PERSONAL.equals(s.optString("mode"))) put("companySeg", true);
                else if (seg > 0) { TripStore.markCompany(this, iso(seg)); if (events != null) events.onTripsChanged(); }
                break;
            }
            case ACTION_RIDE_START:
                if (!PERSONAL.equals(s.optString("mode")) && !s.optBoolean("order")) {
                    closeSegment(now, null);
                    restart(now); put("order", true); put("orderStart", now);
                    put("orderSource", intent.getStringExtra(EXTRA_SOURCE));
                }
                break;
            case ACTION_RIDE_DONE:
                if (s.optBoolean("order")) {
                    String id = closeSegment(now, intent.getStringExtra(EXTRA_SOURCE));
                    restart(now); put("order", false); s.remove("orderStart");
                    if (events != null && id != null) events.onRideCompleted(id);
                    showRideDone(id);
                }
                break;
            case ACTION_RIDE_CANCEL:      // auto-started by mistake: keep the miles, but as "online"
                put("order", false); s.remove("orderStart");
                break;
            default: break;
        }
        if (!ACTION_STOP.equals(action)) {
            put("on", true);
            if (!s.has("segStart")) restart(now);
            startUpdates();
        }
        save();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(idleCheck);
        stopUpdates();
        boolean unexpected = s.optBoolean("on");                  // the driver did not stop it: Android did
        if (s.length() > 0) TripStore.saveState(this, s);
        running = false; live = null;
        if (unexpected) notifyStopped(this, "stopped");
        super.onDestroy();
    }

    // ── location ──
    private void startUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            put("gps", "off");
            return;
        }
        try {
            if ("none".equals(source.kind())) source.start();      // already running: keep it (commands arrive often)
            put("src", source.kind());
            handler.removeCallbacks(idleCheck);
            handler.postDelayed(idleCheck, 60_000);
        } catch (SecurityException | IllegalArgumentException e) {
            put("gps", "off");
        }
    }

    private void stopUpdates() {
        try { source.stop(); } catch (Exception ignored) {}
        handler.removeCallbacks(idleCheck);
    }

    @Override public void onAvailable(boolean available) {
        if (available && "ok".equals(s.optString("gps"))) return;
        put("gps", available ? "wait" : "off"); save();
    }

    @Override public void onSearching() {
        if (!"wait".equals(s.optString("gps"))) { put("gps", "wait"); save(); }
    }

    @Override public void onFix(Location loc) {
        long now = System.currentTimeMillis();
        put("gps", "ok"); put("acc", Math.round(loc.getAccuracy()));
        if (loc.getAccuracy() > 50) { tick(); return; }
        double lat = loc.getLatitude(), lon = loc.getLongitude();
        if (!s.has("startLat")) { put("startLat", round5(lat)); put("startLon", round5(lon)); }
        if (!s.has("lastLat")) { setLast(lat, lon, now); save(); return; }
        double lastLat = s.optDouble("lastLat"), lastLon = s.optDouble("lastLon");
        long lastT = s.optLong("lastT");
        float[] r = new float[1];
        Location.distanceBetween(lastLat, lastLon, lat, lon, r);
        double d = r[0], dt = Math.max(1, (now - lastT) / 1000.0);
        if (now - lastT > IDLE_MS) {                 // GPS was off (tunnel, phone off …)
            if (s.optBoolean("order")) { put("segKm", s.optDouble("segKm", 0) + d / 1000); setLast(lat, lon, now); put("lastMove", now); save(); return; }
            closeSegment(s.optLong("lastMove", now), null);
            restart(now); put("startLat", round5(lat)); put("startLon", round5(lon)); setLast(lat, lon, now);
            save(); return;
        }
        if (d < 8) { tick(); return; }
        if (d / dt > 70) { setLast(lat, lon, now); tick(); return; }
        checkIdle(now);
        put("segKm", s.optDouble("segKm", 0) + d / 1000);
        put("lastMove", now);
        setLast(lat, lon, now);
        maybeAskCompany();
        tick();
    }

    /** Once per drive, when company miles are on: ask whether this personal drive is for the company. */
    private void maybeAskCompany() {
        if (s.optBoolean("coAsked") || s.optDouble("segKm", 0) < ASK_KM) return;
        put("coAsked", true);
        if (!PERSONAL.equals(s.optString("mode")) || s.optBoolean("company") || s.optBoolean("companySeg")) return;
        JSONObject cfg = TripStore.config(this);
        if (!cfg.optBoolean("companyOn")) return;
        showCompanyAsk(s.optLong("segStart"), cfg);
    }

    private void checkIdle(long now) {
        if (s.optBoolean("company") && now - s.optLong("lastMove", now) > CO_AUTO_END_MS) {
            closeSegment(s.optLong("lastMove", now), null);
            s.remove("company"); restart(now); save();
        }
        if (!s.optBoolean("on") || s.optBoolean("order")) return;
        if (s.optDouble("segKm", 0) >= 0.1 && now - s.optLong("lastMove", now) > IDLE_MS) {
            closeSegment(s.optLong("lastMove", now), null);
            restart(now);
            save();
        }
    }

    // ── segments → trips ──
    private String closeSegment(long endTs, String source) {
        String mode = s.optString("mode", PERSONAL);
        boolean order = s.optBoolean("order");
        double km = s.optDouble("segKm", 0);
        String kind = PERSONAL.equals(mode) ? "personal" : order ? "order" : "online";
        if (km < 0.1 && !"order".equals(kind)) return null;
        long start = s.optLong("segStart", endTs);
        if (order && s.has("orderStart")) start = Math.min(start, s.optLong("orderStart"));
        try {
            JSONObject t = new JSONObject();
            String id = "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            t.put("id", id);
            t.put("date", localDate(start));
            t.put("start", iso(start));
            t.put("end", iso(Math.max(endTs, start)));
            t.put("km", Math.round(km * 1000) / 1000.0);
            t.put("app", mode);
            t.put("kind", kind);
            if (PERSONAL.equals(mode) && (s.optBoolean("company") || s.optBoolean("companySeg"))) t.put("company", true);
            t.put("source", source != null ? source : (order && s.has("orderSource") ? s.optString("orderSource") : "native"));
            if (s.has("startLat")) {
                JSONObject l = new JSONObject();
                l.put("lat", s.optDouble("startLat")); l.put("lon", s.optDouble("startLon"));
                t.put("loc", l);
            }
            if (s.has("lastLat")) {                        // where the drive ended (mileage log destination)
                JSONObject l2 = new JSONObject();
                l2.put("lat", round5(s.optDouble("lastLat"))); l2.put("lon", round5(s.optDouble("lastLon")));
                t.put("endLoc", l2);
            }
            TripStore.addTrip(this, t);
            if (events != null) events.onTripsChanged();
            return id;
        } catch (JSONException e) {
            return null;
        }
    }

    private void restart(long now) {
        put("segStart", now); put("segKm", 0d); put("lastMove", now);
        s.remove("companySeg"); s.remove("coAsked");
        if (s.has("lastLat")) { put("startLat", round5(s.optDouble("lastLat"))); put("startLon", round5(s.optDouble("lastLon"))); }
        else { s.remove("startLat"); s.remove("startLon"); }
    }

    private void setLast(double lat, double lon, long t) { put("lastLat", lat); put("lastLon", lon); put("lastT", t); }

    private void put(String k, Object v) { synchronized (s) { try { s.put(k, v); } catch (JSONException ignored) {} } }

    /** After a GPS fix: write to storage at most every 15 s (a fix arrives every 3 s; writing each one wastes battery). */
    private void tick() {
        if (System.currentTimeMillis() - lastPersist >= PERSIST_MS) save(); else publish();
    }

    private String uiSig = "";
    /** After an event (mode change, ride start or end, trip saved): write to storage now. */
    private void save() {
        lastPersist = System.currentTimeMillis();
        put("alive", lastPersist);
        synchronized (s) { TripStore.saveState(this, s); }
        publish();
    }

    private void publish() {
        // redraw the notification and the widget only when what they show changes (every ~0.1 km), not on every GPS fix
        String sig = s.optBoolean("on") + "|" + s.optString("mode") + "|" + s.optBoolean("order") + "|" + s.optBoolean("company") + s.optBoolean("companySeg") + "|" + Math.round(s.optDouble("segKm", 0) * 10) + "|" + s.optString("gps");
        if (!sig.equals(uiSig)) {
            uiSig = sig;
            updateNotification();
            KeptWidget.refresh(this);
        }
        if (events != null) events.onStatus(status(this));
    }

    /** Status in the same shape the web app's tracker uses. */
    public static JSONObject status(Context c) {
        JSONObject cur = live, st = cur != null ? cur : TripStore.state(c), out = new JSONObject();
        synchronized (st) { try {
            out.put("running", running);
            out.put("src", st.optString("src", ""));
            out.put("pending", TripStore.pendingTrips(c).length());
            out.put("on", st.optBoolean("on"));
            out.put("mode", st.optString("mode", PERSONAL));
            out.put("order", st.optBoolean("order"));
            out.put("company", st.optBoolean("company") || st.optBoolean("companySeg"));
            if (st.has("orderStart")) out.put("orderStart", st.optLong("orderStart"));
            out.put("segKm", st.optDouble("segKm", 0));
            out.put("segStart", st.optLong("segStart", System.currentTimeMillis()));
            out.put("gps", st.optString("gps", "wait"));
            out.put("acc", st.optInt("acc", 0));
            out.put("native", true);
        } catch (JSONException ignored) {} }
        return out;
    }

    // ── notifications ──
    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_TRACK, "Mileage tracking", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_RIDES, "Rides", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERT, "Tracking alerts", NotificationManager.IMPORTANCE_HIGH));
    }

    /** Tells the driver that mileage tracking is off although they did not stop it. Tapping opens Kept, which resumes it. */
    static void notifyStopped(Context c, String why) {
        try {
            createChannels(c);
            boolean tr = "tr".equals(TripStore.config(c).optString("lang", "en"));
            String title = tr ? "Kept mil saymayı durdurdu" : "Kept stopped counting miles";
            String text = "restart".equals(why) ? (tr ? "Telefon yeniden başladı. Devam etmek için dokunun." : "Your phone restarted. Tap to resume tracking.")
                    : "update".equals(why) ? (tr ? "Kept güncellendi. Devam etmek için dokunun." : "Kept was updated. Tap to resume tracking.")
                    : (tr ? "Telefon takibi durdurdu. Devam etmek için dokunun." : "Your phone paused tracking. Tap to resume.");
            Notification n = new NotificationCompat.Builder(c, CH_ALERT)
                    .setSmallIcon(R.drawable.ic_kept_notify)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openApp(c))
                    .build();
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_STOPPED, n);
            CrashLog.log("tracking stopped: " + why);
        } catch (Exception e) { CrashLog.record(e); }
    }

    static void cancelStopped(Context c) {
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_STOPPED); } catch (Exception ignored) {}
    }

    static PendingIntent action(Context c, String action, int req) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent openApp(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i == null) i = new Intent();
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildTrackNotification() {
        String mode = s.optString("mode", PERSONAL);
        boolean order = s.optBoolean("order");
        boolean mi = !"km".equals(TripStore.config(this).optString("unit", "mi"));
        double d = s.optDouble("segKm", 0) / (mi ? 1.609344 : 1);
        String dist = String.format(Locale.US, "%.1f %s", d, mi ? "mi" : "km");
        boolean company = PERSONAL.equals(mode) && (s.optBoolean("company") || s.optBoolean("companySeg"));
        JSONObject cfg = TripStore.config(this);
        boolean tr = "tr".equals(cfg.optString("lang", "en"));
        String title = company ? (tr ? "Kept · Şirket sürüşü" : "Kept · Company drive") : PERSONAL.equals(mode) ? "Kept · Personal" : order ? "Kept · On a ride · " + mode : "Kept · Online · " + mode;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_TRACK)
                .setSmallIcon(R.drawable.ic_kept_notify)
                .setContentTitle(title)
                .setContentText("This drive: " + dist)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(this));
        if (company) b.addAction(0, tr ? "Şirket sürüşünü bitir" : "End company drive", action(this, ActionReceiver.CO_DONE, 14));
        else if (PERSONAL.equals(mode) && cfg.optBoolean("companyOn")) b.addAction(0, tr ? "Şirket sürüşü başlat" : "Start company drive", action(this, ActionReceiver.CO_START, 15));
        if (order) b.addAction(0, "Complete ride", action(this, ActionReceiver.RIDE_DONE, 11));
        else if (!PERSONAL.equals(mode)) b.addAction(0, "Start ride", action(this, ActionReceiver.RIDE_START, 12));
        if (!PERSONAL.equals(mode) && !order) b.addAction(0, "Go personal", action(this, ActionReceiver.PERSONAL, 13));
        return b.build();
    }

    /** False when Android refuses: location permission missing (Android 14+) or a blocked background start (Android 12+). */
    private boolean startForegroundNow() {
        try {
            Notification n = buildTrackNotification();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_TRACK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIF_TRACK, n);
            return true;
        } catch (RuntimeException e) {
            CrashLog.record(e);
            return false;
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (s.optBoolean("on")) nm.notify(NOTIF_TRACK, buildTrackNotification());
    }

    /** "Is this a company drive?" with Yes / No. Yes marks this drive, also when it is answered after the drive ended. */
    private void showCompanyAsk(long segStart, JSONObject cfg) {
        try {
            boolean tr = "tr".equals(cfg.optString("lang", "en"));
            String name = cfg.optString("companyName", "");
            String title = tr ? "Bu bir şirket sürüşü mü?" : "Is this a company drive?";
            String text = name.isEmpty() ? (tr ? "Evet derseniz bu sürüş şirket milleri olarak kaydedilir." : "Tap Yes to log this drive as company miles.")
                    : (tr ? "Evet derseniz bu sürüş " + name + " için kaydedilir." : "Tap Yes to log this drive for " + name + ".");
            Intent yes = new Intent(this, ActionReceiver.class).setAction(ActionReceiver.CO_YES).putExtra(ActionReceiver.EXTRA_SEG, segStart);
            Intent no = new Intent(this, ActionReceiver.class).setAction(ActionReceiver.CO_NO);
            int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Notification n = new NotificationCompat.Builder(this, CH_RIDES)
                    .setSmallIcon(R.drawable.ic_kept_notify)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openApp(this))
                    .addAction(0, tr ? "Evet, şirket" : "Yes, company", PendingIntent.getBroadcast(this, 16, yes, f))
                    .addAction(0, tr ? "Hayır" : "No", PendingIntent.getBroadcast(this, 17, no, f))
                    .build();
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_COMPANY, n);
        } catch (Exception e) { CrashLog.record(e); }
    }

    static void cancelCompanyAsk(Context c) {
        try { ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_COMPANY); } catch (Exception ignored) {}
    }

    private void showRideDone(String tripId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_RIDE);
        Notification n = new NotificationCompat.Builder(this, CH_RIDES)
                .setSmallIcon(R.drawable.ic_kept_notify)
                .setContentTitle("Ride complete")
                .setContentText("Tap to add what this ride paid")
                .setAutoCancel(true)
                .setContentIntent(openApp(this))
                .build();
        nm.notify(NOTIF_RIDE, n);
    }

    // ── formatting ──
    private static double round5(double v) { return Math.round(v * 1e5) / 1e5; }

    static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(t));
    }

    static String localDate(long t) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
    }
}
