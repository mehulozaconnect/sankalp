package com.mehul.sankalp;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** One sync run: upload screen time, calls and phone calendar, read your settings and tasks, then plan reminders. */
final class Sync {
    private Sync() { }

    static final int JOB_PERIODIC = 1, JOB_NOW = 2, JOB_CAL = 3;

    static String day(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms)); }

    static long midnight(int addDays) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, addDays);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static String run(Context ctx, int days) throws Exception {
        if (!Api.linked(ctx)) throw new IllegalStateException("Not linked yet. Open Sankalp on this phone, Settings, Link phone app.");
        JSONArray writes = new JSONArray();
        int sessions = 0, calls = 0, events = 0;
        double todayMin = 0;
        for (int i = days - 1; i >= 0; i--) {
            long d0 = midnight(-i), d1 = midnight(-i + 1);
            String date = day(d0);
            JSONObject dj = Collect.day(ctx, date, d0, d1);
            JSONArray ses = dj.getJSONArray("ses");
            if (ses.length() == 0 && dj.getJSONArray("calls").length() == 0) continue;
            sessions += ses.length();
            calls += dj.getJSONArray("calls").length();
            if (i == 0) for (int k = 0; k < ses.length(); k++) { JSONArray s = ses.getJSONArray(k); todayMin += (s.getLong(2) - s.getLong(1)) / 60000.0; }
            writes.put(Api.write(Api.itemName(ctx, "phone%3A" + date), dj.toString(), "phone"));
        }
        JSONObject cal = null;
        if (Collect.calAllowed(ctx)) {
            cal = Collect.calendar(ctx, midnight(-7), midnight(15));
            events = cal.getJSONArray("ev").length();
            writes.put(Api.write(Api.itemName(ctx, "pcal%3Aall"), cal.toString(), "pcal"));
            Api.p(ctx).edit().putString("pcal", cal.toString()).apply();
        }
        Api.commit(ctx, writes);

        JSONObject prefs = null, coach = null;
        try { prefs = Api.getD(ctx, Api.itemName(ctx, "meta%3Aprefs")); } catch (Exception ignored) { }
        try { coach = Api.getD(ctx, Api.itemName(ctx, "meta%3Acoach")); } catch (Exception ignored) { }
        SharedPreferences.Editor e = Api.p(ctx).edit();
        if (prefs != null) {
            e.putString("prefsJson", prefs.toString());
            e.putBoolean("quietOn", prefs.optBoolean("quietOn", false)).putString("quietStart", prefs.optString("quietStart", "22:00")).putString("quietEnd", prefs.optString("quietEnd", "07:30"))
             .putString("workStart", prefs.optString("workStart", "09:30")).putString("shutdown", prefs.optString("shutdown", "19:00")).putString("morning", prefs.optString("morning", "08:30"))
             .putInt("meetBefore", prefs.optInt("meetBefore", 10))
             .putString("coach", prefs.optJSONObject("coach") == null ? "{}" : prefs.optJSONObject("coach").toString())
             .putString("appmap", prefs.optJSONObject("appmap") == null ? "{}" : prefs.optJSONObject("appmap").toString());
        }
        if (coach != null) e.putString("coachBrief", coach.toString());
        String rvNow = "";
        try { JSONObject rv = Api.getD(ctx, Api.itemName(ctx, "meta%3Arv")); if (rv != null) rvNow = rv.optString("v", ""); } catch (Exception ignored) { }
        JSONArray tasks = Api.list(ctx, "task");
        e.putString("brief", brief(tasks).toString());
        e.apply();
        int alarms = Alarms.plan(ctx, tasks, prefs, cal);
        try { ack(ctx, rvNow); } catch (Exception ignored) { }
        Coach.schedule(ctx);

        String sum = "Screen time today " + Alarms.mins((int) Math.round(todayMin)) + ", " + calls + " calls, " + events + " calendar events. " + alarms + " reminders set for the next 2 days.";
        Api.p(ctx).edit().putLong("lastSync", System.currentTimeMillis()).putString("lastSum", sum).remove("lastErr").apply();
        return sum;
    }

    /** Cheap check every few minutes: if your reminders changed in Sankalp, fetch tasks and re-plan alarms right away. */
    static void quick(Context ctx) throws Exception {
        if (!Api.linked(ctx)) return;
        JSONObject rv = Api.getD(ctx, Api.itemName(ctx, "meta%3Arv"));
        if (rv == null) return;
        String v = rv.optString("v", "");
        SharedPreferences p = Api.p(ctx);
        if (v.isEmpty() || v.equals(p.getString("rv", ""))) return;
        JSONArray tasks = Api.list(ctx, "task");
        JSONObject prefs = null, cal = null;
        try { prefs = new JSONObject(p.getString("prefsJson", "{}")); } catch (Exception ignored) { }
        try { cal = new JSONObject(p.getString("pcal", "{}")); } catch (Exception ignored) { }
        Alarms.plan(ctx, tasks, prefs, cal);
        p.edit().putString("brief", brief(tasks).toString()).apply();
        ack(ctx, v);
    }

    /** Tells Sankalp the phone now has these reminders, so the web app stops sending its own. */
    static void ack(Context ctx, String v) throws Exception {
        if (v == null || v.isEmpty()) return;
        Api.commit(ctx, new JSONArray().put(Api.write(Api.itemName(ctx, "meta%3Aack"), new JSONObject().put("v", v).put("at", System.currentTimeMillis()).toString(), "meta")));
        Api.p(ctx).edit().putString("rv", v).apply();
    }

    /** What the coach needs offline: the top open task, P1 progress today, tasks done today and this week. */
    static JSONObject brief(JSONArray tasks) throws Exception {
        String td = day(System.currentTimeMillis());
        long d0 = midnight(0), w0 = midnight(-6);
        JSONObject top = null; int topP = 9; String topDate = "9999";
        int p1 = 0, p1d = 0, doneToday = 0, doneWeek = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.getJSONObject(i).getJSONObject("d");
            if (t.has("orig") && !t.isNull("orig") && !t.optBoolean("done")) continue;
            String date = t.isNull("date") ? "" : t.optString("date", "");
            boolean done = t.optBoolean("done", false);
            long doneAt = t.optLong("doneAt", 0);
            if (done && doneAt >= d0) doneToday++;
            if (done && doneAt >= w0) doneWeek++;
            int pr = t.optInt("priority", 4);
            if (pr == 1 && (date.equals(td) || (done && doneAt >= d0) || (!done && !date.isEmpty() && date.compareTo(td) < 0))) { p1++; if (done) p1d++; }
            if (!done && !date.isEmpty() && date.compareTo(td) <= 0 && !t.optBoolean("someday", false)) {
                if (pr < topP || (pr == topP && date.compareTo(topDate) < 0)) { topP = pr; topDate = date; top = t; }
            }
        }
        JSONObject b = new JSONObject().put("date", td).put("p1", p1).put("p1d", p1d).put("doneToday", doneToday).put("doneWeek", doneWeek);
        if (top != null) b.put("topId", top.optString("id")).put("topTitle", top.optString("title")).put("topP", topP);
        return b;
    }

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        ComponentName cn = new ComponentName(ctx, SyncJob.class);
        watchCalendar(ctx);
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        }
    }

    /** Wakes a sync a few seconds after anything in the phone's calendar changes (new meeting, moved, accepted). One-shot, so it is re-armed after each run. */
    static void watchCalendar(Context ctx) { watchCalendar(ctx, false); }

    static void watchCalendar(Context ctx, boolean force) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!force && js.getPendingJob(JOB_CAL) != null) return;
        js.schedule(new JobInfo.Builder(JOB_CAL, new ComponentName(ctx, SyncJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(android.provider.CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(10 * 1000L)
                .setTriggerContentMaxDelay(60 * 1000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    static void soon(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(ctx, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60 * 1000L)
                .build());
    }
}
