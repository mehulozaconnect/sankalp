package com.mehul.sankalp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/** A reminder is due: check the task is still open, show it, and set the "remind again" follow-up. */
public class AlarmReceiver extends BroadcastReceiver {
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
                if (a.optBoolean("meet")) { if (!Alarms.quiet(ctx)) Alarms.showMeet(ctx, a); return; }
                boolean open = true;
                try {
                    JSONObject t = Api.getD(ctx, a.optString("name"));
                    if (t == null || t.optBoolean("done", false)) open = false;
                    else if (a.has("sub")) {
                        JSONArray subs = t.optJSONArray("subs");
                        if (subs != null) for (int i = 0; i < subs.length(); i++) {
                            JSONObject c = subs.getJSONObject(i);
                            if (a.optString("sub").equals(c.optString("id")) && c.optBoolean("d", false)) open = false;
                        }
                    }
                } catch (Exception offline) {
                    // No network: remind anyway.
                }
                if (open) {
                    if (!Alarms.quiet(ctx) || a.optInt("prio") == 1) Alarms.show(ctx, a);
                    Alarms.scheduleNag(ctx, a);
                }
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }
}
