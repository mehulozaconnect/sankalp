package com.mehul.sankalp;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs the coach every few minutes, and handles "5 more min" on a nudge. */
public class CoachReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final Context ctx = context.getApplicationContext();
        if ("snooze5".equals(intent.getAction())) {
            Api.p(ctx).edit().putLong("ngSnooze", System.currentTimeMillis() + 5 * 60000L).apply();
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(101);
            return;
        }
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try { Coach.tick(ctx); } catch (Exception ignored) { }
            finally { Coach.schedule(ctx); pr.finish(); }
        }).start();
    }
}
