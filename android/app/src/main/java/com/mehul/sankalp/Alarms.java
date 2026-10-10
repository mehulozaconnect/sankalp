package com.mehul.sankalp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/** Exact alarms for task and subtask reminders, with Done / Snooze and "remind again until done". */
final class Alarms {
    private Alarms() { }

    static final String SITE = "https://mehulozaconnect.github.io/sankalp/";
    static final String CH = "reminders";
    static final int NAG_MAX = 12;

    static long ts(String date, String hm) {
        String[] d = date.split("-");
        String[] t = (hm == null || hm.isEmpty() ? "09:00" : hm).split(":");
        Calendar c = Calendar.getInstance();
        c.set(Integer.parseInt(d[0]), Integer.parseInt(d[1]) - 1, Integer.parseInt(d[2]), Integer.parseInt(t[0]), Integer.parseInt(t[1]), 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String clock(String hm) {
        if (hm == null || hm.isEmpty()) return "";
        String[] t = hm.split(":");
        int h = Integer.parseInt(t[0]);
        String ap = h >= 12 ? "PM" : "AM";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + t[1] + " " + ap;
    }

    static String mins(int m) {
        if (m < 60) return m + " min";
        if (m % 60 == 0) return (m / 60) + " h";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    static String body(int m, String time) {
        if (m == 0) return time != null && !time.isEmpty() ? "Now, " + clock(time) : "Due today";
        if (m >= 1440) return (m == 1440 ? "Tomorrow" : "In " + (m / 1440) + " days") + (time != null && !time.isEmpty() ? " at " + clock(time) : "");
        return "In " + mins(m) + ", at " + clock(time == null || time.isEmpty() ? "09:00" : time);
    }

    /** Works out every reminder in the next 48 hours from the task list and (re)schedules them. Returns how many. */
    static int plan(Context ctx, JSONArray tasks, JSONObject prefs, JSONObject cal) throws Exception {
        long now = System.currentTimeMillis(), until = now + 48L * 3600000L;
        int nagDef = prefs == null ? 10 : prefs.optInt("nagDef", 10);
        JSONObject next = new JSONObject();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject it = tasks.getJSONObject(i);
            String name = it.getString("name");
            JSONObject t = it.getJSONObject("d");
            if (t.optBoolean("done", false)) continue;
            String tid = t.optString("id", "");
            String title = t.optString("title", "Task");
            int prio = t.optInt("priority", 4);
            int nag = t.has("nag") && !t.isNull("nag") ? t.optInt("nag", nagDef) : nagDef;
            boolean rep = t.has("repeat") && !t.isNull("repeat");
            String date = t.isNull("date") ? null : t.optString("date", null);
            String time = t.isNull("time") ? null : t.optString("time", null);
            JSONArray rem = t.optJSONArray("reminders");
            if (date != null && date.length() == 10 && rem != null) {
                for (int r = 0; r < rem.length(); r++) {
                    int m = rem.optInt(r, 0);
                    long at = ts(date, time) - m * 60000L;
                    if (at > now && at < until) put(next, tid + "|" + m, at, title, body(m, time), prio, m <= 60 ? nag : 0, name, null, tid, rep);
                }
            }
            long snz = t.optLong("snoozeUntil", 0);
            if (snz > now && snz < until) put(next, tid + "|z", snz, title, "Snoozed reminder", prio, nag, name, null, tid, rep);
            JSONArray subs = t.optJSONArray("subs");
            if (subs != null) for (int s = 0; s < subs.length(); s++) {
                JSONObject c = subs.getJSONObject(s);
                String sat = c.optString("at", "");
                if (c.optBoolean("d", false) || sat.length() < 16) continue;
                long at = ts(sat.substring(0, 10), sat.substring(11, 16));
                if (at > now && at < until) put(next, tid + "|s|" + c.optString("id"), at, c.optString("t", "Subtask"), "Part of " + title, prio, nag, name, c.optString("id"), tid, false);
            }
        }
        int mb = prefs == null ? 10 : prefs.optInt("meetBefore", 10);
        if (cal != null && mb > 0) {
            JSONArray ev = cal.optJSONArray("ev");
            if (ev != null) for (int i = 0; i < ev.length(); i++) {
                JSONArray x = ev.getJSONArray(i);
                if (x.optBoolean(4) || x.optInt(7, 0) == 2 || x.optString(5, "").toLowerCase().matches(".*(holiday|birthday|contacts).*")) continue;
                long st = x.getLong(2), at = st - mb * 60000L;
                if (at <= now || at > until) continue;
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
                String id = "m|" + x.get(0) + "|" + st;
                JSONObject a = new JSONObject().put("id", id).put("at", at).put("title", x.optString(1, "Meeting"))
                        .put("body", "Starts at " + clock(f.format(new java.util.Date(st))) + " (in " + mb + " min)").put("meet", true).put("join", x.optString(6, "")).put("tid", id);
                next.put(id, a);
            }
        }
        JSONObject old = new JSONObject(Api.p(ctx).getString("alarms", "{}"));
        Iterator<String> ks = old.keys();
        while (ks.hasNext()) { String k = ks.next(); if (!next.has(k) || next.getJSONObject(k).getLong("at") != old.getJSONObject(k).getLong("at")) cancel(ctx, k); }
        Iterator<String> nk = next.keys();
        while (nk.hasNext()) { String k = nk.next(); schedule(ctx, next.getJSONObject(k)); }
        Api.p(ctx).edit().putString("alarms", next.toString()).apply();
        return next.length();
    }

    static void put(JSONObject map, String id, long at, String title, String body, int prio, int nag, String name, String sub, String tid, boolean rep) throws Exception {
        JSONObject a = new JSONObject();
        a.put("id", id).put("at", at).put("title", title).put("body", body).put("prio", prio).put("nag", nag)
                .put("name", name).put("tid", tid).put("rep", rep).put("n", 0);
        if (sub != null) a.put("sub", sub);
        map.put(id, a);
    }

    static PendingIntent alarmPi(Context ctx, String id, String json) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setData(Uri.parse("sankalp://alarm/" + Uri.encode(id)));
        if (json != null) i.putExtra("a", json);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context ctx, JSONObject a) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        String id = a.optString("id");
        long at = a.optLong("at");
        PendingIntent pi = alarmPi(ctx, id, a.toString());
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    static void cancel(Context ctx, String id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(alarmPi(ctx, id, null));
    }

    /** After a reboot: put back the alarms we had. */
    static void restore(Context ctx) {
        try {
            JSONObject m = new JSONObject(Api.p(ctx).getString("alarms", "{}"));
            long now = System.currentTimeMillis();
            Iterator<String> ks = m.keys();
            while (ks.hasNext()) { JSONObject a = m.getJSONObject(ks.next()); if (a.optLong("at") > now) schedule(ctx, a); }
        } catch (Exception ignored) { }
    }

    static void channel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Task reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Reminders for your Sankalp tasks");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[] { 0, 250, 150, 250 });
            nm.createNotificationChannel(ch);
        }
    }

    static int notifId(JSONObject a) { return ("n" + a.optString("tid") + a.optString("sub", "")).hashCode(); }

    static void show(Context ctx, JSONObject a) {
        channel(ctx);
        String tid = a.optString("tid");
        int nid = notifId(a);
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(SITE + "?open=" + Uri.encode(tid)));
        PendingIntent openPi = PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent donePi;
        if (a.optBoolean("rep") && !a.has("sub")) {
            Intent d = new Intent(Intent.ACTION_VIEW, Uri.parse(SITE + "?done=" + Uri.encode(tid)));
            donePi = PendingIntent.getActivity(ctx, nid + 1, d, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            donePi = action(ctx, "done", a, nid + 1);
        }
        PendingIntent snzPi = action(ctx, "snooze", a, nid + 2);
        Icon ic = Icon.createWithResource(ctx, R.drawable.ic_stat);
        int n = a.optInt("n", 0);
        String text = n > 0 ? "Still not done. Reminder " + (n + 1) : a.optString("body");
        Notification.Builder b = new Notification.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFF2F7BEA)
                .setContentTitle((a.optInt("prio") == 1 ? "P1 · " : "") + a.optString("title"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(ic, "Done", donePi).build())
                .addAction(new Notification.Action.Builder(ic, "Snooze 10 min", snzPi).build());
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(nid, b.build()); } catch (SecurityException ignored) { }
    }

    static void showMeet(Context ctx, JSONObject a) {
        channel(ctx);
        int nid = notifId(a);
        String join = a.optString("join", "");
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(join.isEmpty() ? SITE + "#today" : join));
        PendingIntent pi = PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_stat).setColor(0xFF8B5CF6)
                .setContentTitle(a.optString("title")).setContentText(a.optString("body"))
                .setCategory(Notification.CATEGORY_EVENT).setContentIntent(pi).setAutoCancel(true);
        if (!join.isEmpty()) b.addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), "Join", pi).build());
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(nid, b.build()); } catch (SecurityException ignored) { }
    }

    static PendingIntent action(Context ctx, String act, JSONObject a, int req) {
        Intent i = new Intent(ctx, ActionReceiver.class).setAction(act).setData(Uri.parse("sankalp://act/" + act + "/" + Uri.encode(a.optString("id"))));
        i.putExtra("a", a.toString());
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The follow-up alarm for "remind again until done". */
    static void scheduleNag(Context ctx, JSONObject a) {
        try {
            int nag = a.optInt("nag", 0), n = a.optInt("n", 0);
            String base = a.optString("id").replaceAll("\\|n$", "");
            if (nag <= 0 || n >= NAG_MAX) return;
            JSONObject x = new JSONObject(a.toString());
            x.put("id", base + "|n").put("n", n + 1).put("at", System.currentTimeMillis() + nag * 60000L);
            schedule(ctx, x);
        } catch (Exception ignored) { }
    }

    static void cancelNag(Context ctx, JSONObject a) {
        String base = a.optString("id").replaceAll("\\|(n|snz)$", "");
        cancel(ctx, base + "|n");
        cancel(ctx, base + "|snz");
    }

    static boolean quiet(Context ctx) {
        android.content.SharedPreferences p = Api.p(ctx);
        if (!p.getBoolean("quietOn", false)) return false;
        String qs = p.getString("quietStart", "22:00"), qe = p.getString("quietEnd", "07:30");
        Calendar c = Calendar.getInstance();
        String hm = String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        return qs.compareTo(qe) <= 0 ? (hm.compareTo(qs) >= 0 && hm.compareTo(qe) < 0) : (hm.compareTo(qs) >= 0 || hm.compareTo(qe) < 0);
    }

    static Set<String> ids(JSONObject m) { Set<String> s = new HashSet<>(); Iterator<String> k = m.keys(); while (k.hasNext()) s.add(k.next()); return s; }
}
