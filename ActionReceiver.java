package com.mehul.sankalp;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/** Done / Snooze buttons on a reminder. Done is written straight to your Sankalp data. */
public class ActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String raw = intent.getStringExtra("a");
        final String act = intent.getAction();
        final int mins = Math.max(1, intent.getIntExtra("mins", 10));
        if (raw == null || act == null) return;
        final Context ctx = context.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                JSONObject a = new JSONObject(raw);
                NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(Alarms.notifId(a));
                Alarms.cancelNag(ctx, a);
                if ("snooze".equals(act)) {
                    JSONObject x = new JSONObject(a.toString());
                    String base = a.optString("id").replaceAll("\\|(n|snz)$", "");
                    x.put("id", base + "|snz").put("n", 0).put("body", "Snoozed reminder").put("at", System.currentTimeMillis() + mins * 60000L);
                    Alarms.schedule(ctx, x);
                } else if ("done".equals(act)) {
                    String name = a.optString("name");
                    JSONObject t = Api.getD(ctx, name);
                    if (t != null) {
                        if (a.has("sub")) {
                            JSONArray subs = t.optJSONArray("subs");
                            if (subs != null) for (int i = 0; i < subs.length(); i++) {
                                JSONObject c = subs.getJSONObject(i);
                                if (a.optString("sub").equals(c.optString("id"))) { c.put("d", true); c.put("nagAt", 0); }
                            }
                        } else {
                            t.put("done", true);
                            t.put("doneAt", System.currentTimeMillis());
                            t.put("nagAt", 0);
                            t.put("snoozeUntil", 0);
                        }
                        Api.commit(ctx, new JSONArray().put(Api.write(name, t.toString(), "task")));
                    }
                }
            } catch (Exception e) {
                Api.p(ctx).edit().putString("lastErr", "Could not save Done: " + e.getMessage()).apply();
            } finally {
                pr.finish();
            }
        }).start();
    }
}
