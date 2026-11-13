package io.usebuildflow.kept;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * Home-screen widget: today's take-home (sent by the web app through KeptNative.setWidget),
 * the tracking status, and one button (Start ride / Complete ride, or Open Kept).
 */
public class KeptWidget extends AppWidgetProvider {
    static final String PREF = "kept_widget";

    /** Called from the web app whenever today's numbers change. */
    public static void save(Context c, String amount, String label, String startLabel, String doneLabel, String openLabel) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        if (amount != null) e.putString("amount", amount);
        if (label != null) e.putString("label", label);
        if (startLabel != null) e.putString("start", startLabel);
        if (doneLabel != null) e.putString("done", doneLabel);
        if (openLabel != null) e.putString("open", openLabel);
        e.putLong("at", System.currentTimeMillis());
        e.apply();
        refresh(c);
    }

    /** Redraw every placed widget (also called by the tracking service when the status changes). */
    public static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        if (m == null) return;
        int[] ids = m.getAppWidgetIds(new ComponentName(c, KeptWidget.class));
        if (ids != null && ids.length > 0) update(c, m, ids);
    }

    @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { update(c, m, ids); }

    static void update(Context c, AppWidgetManager m, int[] ids) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        JSONObject st = TripStore.state(c);
        boolean on = st.optBoolean("on", false), order = st.optBoolean("order", false);
        String mode = st.optString("mode", TrackingService.PERSONAL);
        boolean personal = TrackingService.PERSONAL.equals(mode);
        String status = !on ? "Tracking paused" : order ? "On a ride · " + mode : personal ? "Personal driving" : "Online · " + mode;
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.kept_widget);
            v.setTextViewText(R.id.w_amount, p.getString("amount", "—"));
            v.setTextViewText(R.id.w_label, p.getString("label", "Today's take-home"));
            v.setTextViewText(R.id.w_status, status);
            v.setOnClickPendingIntent(R.id.w_root, TrackingService.openApp(c));
            if (on && order) {
                v.setTextViewText(R.id.w_btn, p.getString("done", "Complete ride"));
                v.setOnClickPendingIntent(R.id.w_btn, TrackingService.action(c, ActionReceiver.RIDE_DONE, 31));
            } else if (on && !personal) {
                v.setTextViewText(R.id.w_btn, p.getString("start", "Start ride"));
                v.setOnClickPendingIntent(R.id.w_btn, TrackingService.action(c, ActionReceiver.RIDE_START, 32));
            } else {
                v.setTextViewText(R.id.w_btn, p.getString("open", "Open Kept"));
                v.setOnClickPendingIntent(R.id.w_btn, TrackingService.openApp(c));
            }
            m.updateAppWidget(id, v);
        }
    }
}
