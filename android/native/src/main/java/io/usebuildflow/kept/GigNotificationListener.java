package io.usebuildflow.kept;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads notifications from gig apps (the user grants "Notification access" once) to:
 *  - switch Kept to "online for <app>" while the gig app shows its ongoing "you're online" notification
 *  - start a ride when an order is accepted, and complete it when the app says it was delivered
 * Keyword lists are a starting point. Gig apps change their wording, so every notification from a watched app
 * is kept in a learn-mode log (Settings → Notification log) and the web app can send replacement rules.
 * Nothing leaves the phone: the log and rules are stored on the device only.
 */
public class GigNotificationListener extends NotificationListenerService {

    static final class Rule {
        final List<String> apps; final List<String> start; final List<String> done;
        Rule(List<String> apps, List<String> start, List<String> done) { this.apps = apps; this.start = start; this.done = done; }
    }

    private static final List<String> START = Arrays.asList("accepted", "new order", "new delivery", "head to pickup", "heading to pickup",
            "pick up", "pickup at", "picking up", "new request", "trip request accepted", "batch accepted", "you have a new");
    private static final List<String> DONE = Arrays.asList("delivered", "delivery complete", "order complete", "trip complete", "trip ended",
            "drop-off complete", "dropoff complete", "you earned", "great job", "nice work", "completed");

    /** Default package → app names. Uber and Uber Eats share the Uber Driver app. */
    static Map<String, Rule> defaults() {
        Map<String, Rule> m = new HashMap<>();
        m.put("com.doordash.driverapp", new Rule(Arrays.asList("DoorDash"), START, DONE));
        m.put("com.ubercab.driver", new Rule(Arrays.asList("Uber", "Uber Eats"), START, DONE));
        m.put("com.lyft.android.driver", new Rule(Arrays.asList("Lyft"), START, DONE));
        m.put("com.instacart.shopper", new Rule(Arrays.asList("Instacart"), START, DONE));
        m.put("com.grubhub.driver", new Rule(Arrays.asList("Grubhub"), START, DONE));
        m.put("com.amazon.flex.rabbit", new Rule(Arrays.asList("Amazon Flex"), START, DONE));
        return m;
    }

    private static long lastAction = 0;

    /** Defaults merged with rules sent from the web app: config.rules.apps = { pkg: { apps:[…], start:[…], done:[…] } } */
    static Map<String, Rule> rules(Context c) {
        Map<String, Rule> m = defaults();
        JSONObject r = TripStore.config(c).optJSONObject("rules");
        JSONObject apps = r != null ? r.optJSONObject("apps") : null;
        if (apps != null) {
            Iterator<String> it = apps.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONObject o = apps.optJSONObject(pkg);
                if (o == null) continue;
                Rule base = m.get(pkg);
                m.put(pkg, new Rule(list(o.optJSONArray("apps"), base != null ? base.apps : new ArrayList<String>()),
                        list(o.optJSONArray("start"), base != null ? base.start : START),
                        list(o.optJSONArray("done"), base != null ? base.done : DONE)));
            }
        }
        return m;
    }

    private static List<String> list(JSONArray a, List<String> fallback) {
        if (a == null || a.length() == 0) return fallback;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i).toLowerCase(Locale.ROOT));
        return out;
    }

    private static boolean any(String text, List<String> words) {
        for (String w : words) if (text.contains(w)) return true;
        return false;
    }

    /** Which of the user's apps this notification belongs to (null = not one they drive for). */
    private static String appFor(Context c, Rule rule, String text) {
        JSONArray mine = TripStore.config(c).optJSONArray("apps");
        List<String> chosen = new ArrayList<>();
        for (String a : rule.apps) {
            boolean ok = mine == null;
            if (mine != null) for (int i = 0; i < mine.length(); i++) if (a.equalsIgnoreCase(mine.optString(i))) ok = true;
            if (ok) chosen.add(a);
        }
        if (chosen.isEmpty()) return null;
        if (chosen.size() > 1 && chosen.contains("Uber Eats") && (text.contains("eats") || text.contains("deliver") || text.contains("order"))) return "Uber Eats";
        if (chosen.size() > 1 && chosen.contains("Uber")) return "Uber";
        return chosen.get(0);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        Context c = getApplicationContext();
        Rule rule = rules(c).get(sbn.getPackageName());
        if (rule == null) return;
        Bundle x = sbn.getNotification().extras;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String body = str(x.getCharSequence(Notification.EXTRA_TEXT)) + " " + str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String text = (title + " " + body).toLowerCase(Locale.ROOT);
        String app = appFor(c, rule, text);
        String act = null;

        JSONObject cfg = TripStore.config(c);
        JSONObject r = cfg.optJSONObject("rules");
        boolean auto = r == null || r.optBoolean("enabled", true);
        JSONObject st = TripStore.state(c);
        String mode = st.optString("mode", TrackingService.PERSONAL);
        boolean order = st.optBoolean("order");
        long now = System.currentTimeMillis();

        if (app != null && st.optBoolean("on", true)) {
            if (order && any(text, rule.done) && now - lastAction > 20_000) {
                act = "complete";
                lastAction = now;
                TrackingService.command(c, TrackingService.ACTION_RIDE_DONE, null, "notification");
            } else if (!order && any(text, rule.start) && now - lastAction > 20_000) {
                lastAction = now;
                if (auto) {
                    act = "start";
                    TrackingService.command(c, TrackingService.ACTION_MODE, app, null);
                    TrackingService.command(c, TrackingService.ACTION_RIDE_START, null, "notification");
                    rideStartedNotice(c, app, true);
                } else {
                    act = "ask";
                    rideStartedNotice(c, app, false);
                }
            } else if (sbn.isOngoing() && TrackingService.PERSONAL.equals(mode)) {
                act = "online";              // the gig app's "you're online" notification
                TrackingService.command(c, TrackingService.ACTION_MODE, app, null);
            }
        }
        try {
            JSONObject item = new JSONObject();
            item.put("time", TrackingService.iso(now));
            item.put("pkg", sbn.getPackageName());
            item.put("app", app == null ? "" : app);
            item.put("title", title);
            item.put("text", body.trim());
            item.put("ongoing", sbn.isOngoing());
            if (act != null) item.put("action", act);
            TripStore.log(c, item);
        } catch (JSONException ignored) {}
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        Context c = getApplicationContext();
        Rule rule = rules(c).get(sbn.getPackageName());
        if (rule == null || !sbn.isOngoing()) return;
        JSONObject st = TripStore.state(c);
        String mode = st.optString("mode", TrackingService.PERSONAL);
        // the gig app went offline → back to Personal (never in the middle of a ride)
        if (!st.optBoolean("order") && rule.apps.contains(mode)) TrackingService.command(c, TrackingService.ACTION_MODE, TrackingService.PERSONAL, null);
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString(); }

    /** started=true: "Ride started" with Not a ride / Complete. started=false: ask with a Start ride button. */
    static void rideStartedNotice(Context c, String app, boolean started) {
        TrackingService.createChannels(c);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, TrackingService.CH_RIDES)
                .setSmallIcon(R.drawable.ic_kept_notify)
                .setAutoCancel(true)
                .setContentIntent(TrackingService.openApp(c));
        if (started) {
            b.setContentTitle("Ride started · " + app).setContentText("Kept is counting these as " + app + " order miles.")
             .addAction(0, "Not a ride", withMode(c, ActionReceiver.UNDO_RIDE, app, 21))
             .addAction(0, "Complete", TrackingService.action(c, ActionReceiver.RIDE_DONE, 22));
        } else {
            b.setContentTitle(app + " order?").setContentText("Start a ride so Kept counts the miles.")
             .addAction(0, "Start ride", withMode(c, ActionReceiver.START_FOR_APP, app, 23));
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(TrackingService.NOTIF_RIDE, b.build());
    }

    private static android.app.PendingIntent withMode(Context c, String action, String mode, int req) {
        Intent i = new Intent(c, ActionReceiver.class).setAction(action).putExtra(TrackingService.EXTRA_MODE, mode);
        return android.app.PendingIntent.getBroadcast(c, req, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }
}
