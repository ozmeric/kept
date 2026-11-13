package io.usebuildflow.kept;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Buttons on Kept's notifications: Start ride / Complete ride / Go personal / Undo auto-start. */
public class ActionReceiver extends BroadcastReceiver {
    public static final String RIDE_START = "io.usebuildflow.kept.act.RIDE_START";
    public static final String RIDE_DONE = "io.usebuildflow.kept.act.RIDE_DONE";
    public static final String PERSONAL = "io.usebuildflow.kept.act.PERSONAL";
    public static final String START_FOR_APP = "io.usebuildflow.kept.act.START_FOR_APP";   // extra "mode"
    public static final String UNDO_RIDE = "io.usebuildflow.kept.act.UNDO_RIDE";           // extra "mode"

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (a == null) return;
        String mode = i.getStringExtra(TrackingService.EXTRA_MODE);
        switch (a) {
            case RIDE_START:
                TrackingService.command(c, TrackingService.ACTION_RIDE_START, null, "button");
                break;
            case RIDE_DONE:
                TrackingService.command(c, TrackingService.ACTION_RIDE_DONE, null, "button");
                break;
            case PERSONAL:
                TrackingService.command(c, TrackingService.ACTION_MODE, TrackingService.PERSONAL, null);
                break;
            case START_FOR_APP:
                TrackingService.command(c, TrackingService.ACTION_MODE, mode, null);
                TrackingService.command(c, TrackingService.ACTION_RIDE_START, null, "notification");
                break;
            case UNDO_RIDE:
                // an auto-started ride that wasn't one: the miles so far stay as "online"
                TrackingService.command(c, TrackingService.ACTION_RIDE_CANCEL, null, null);
                break;
            default: return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(TrackingService.NOTIF_RIDE);
    }
}
