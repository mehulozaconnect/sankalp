package com.mehul.sankalp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/** A reminder is due: check the task is still open, show it, and set the "remind again" follow-up. */
public class AlarmReceiver extends BroadcastReceiver {
    /** True when this alarm no longer matches the task: it was rescheduled to later, or its reminder was removed. */
    static boolean moved(JSONObject t, JSONObject a) {
        try {
            if (t.optBoolean("someday", false)) return true;
            String date = t.isNull("date") ? "" : t.optString("date", "");
            if (date.length() != 10) return true;
            String time = t.isNull("time") ? null : t.optString("time", null);
            long due = Alarms.ts(date, time), now = System.currentTimeMillis();
            String id = a.optString("id");
            String[] parts = id.split("\\|");
            boolean follow = id.endsWith("|n") || id.endsWith("|snz") || id.endsWith("|z");
            if (follow) {
                if (id.contains("|z") && !id.endsWith("|n") && !id.endsWith("|snz") && t.optLong("snoozeUntil", 0) == 0) return true;
                return due > now + 60000L; // moved to later: no more "still not done"
            }
            if (parts.length >= 2) {
                int m = Integer.parseInt(parts[1]);
                long expect = due - m * 60000L;
                return Math.abs(expect - a.optLong("at", expect)) > 120000L;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static boolean stillLater(String at, JSONObject a) {
        try {
            if (at.length() < 16) return true;
            long due = Alarms.ts(at.substring(0, 10), at.substring(11, 16)), now = System.currentTimeMillis();
            String id = a.optString("id");
            if (id.endsWith("|n") || id.endsWith("|snz")) return due > now + 60000L;
            return Math.abs(due - a.optLong("at", due)) > 120000L;
        } catch (Exception e) { return false; }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String raw = intent.getStringExtra("a");
        if (raw == null) return;
        final Context ctx = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                JSONObject a = new JSONObject(raw);
                if (a.optBoolean("test")) { Alarms.show(ctx, a); return; }
                if (a.optBoolean("meet")) { if (a.optBoolean("date") || !Alarms.quiet(ctx)) Alarms.showMeet(ctx, a); return; }
                boolean open = true;
                try {
                    JSONObject t = Api.getD(ctx, a.optString("name"));
                    if (t == null || t.optBoolean("done", false)) open = false;
                    else if (a.has("sub")) {
                        JSONArray subs = t.optJSONArray("subs");
                        if (subs != null) for (int i = 0; i < subs.length(); i++) {
                            JSONObject c = subs.getJSONObject(i);
                            if (!a.optString("sub").equals(c.optString("id"))) continue;
                            if (c.optBoolean("d", false)) open = false;
                            else if (stillLater(c.optString("at", ""), a)) open = false;
                        }
                    } else if (moved(t, a)) open = false;
                } catch (Exception offline) {
                    // No network: remind anyway.
                }
                if (open) {
                    if (!Alarms.quiet(ctx) || a.optInt("prio") == 1) Alarms.show(ctx, a);
                    Alarms.scheduleNag(ctx, a);
                } else {
                    Alarms.cancelNag(ctx, a);
                    ((android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(Alarms.notifId(a));
                }
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
