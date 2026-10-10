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

/** One sync run: upload screen time and calls for the last few days, then plan reminder alarms. */
final class Sync {
    private Sync() { }

    static final int JOB_PERIODIC = 1, JOB_NOW = 2;

    static String run(Context ctx, int days) throws Exception {
        if (!Api.linked(ctx)) throw new IllegalStateException("Not linked yet. Open Sankalp on this phone, Settings, Link phone app.");
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        JSONArray writes = new JSONArray();
        int sessions = 0, calls = 0;
        double todayMin = 0;
        for (int i = days - 1; i >= 0; i--) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_MONTH, -i);
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            long d0 = c.getTimeInMillis();
            c.add(Calendar.DAY_OF_MONTH, 1);
            long d1 = c.getTimeInMillis();
            String date = f.format(new Date(d0));
            JSONObject day = Collect.day(ctx, date, d0, d1);
            JSONArray ses = day.getJSONArray("ses");
            if (ses.length() == 0 && day.getJSONArray("calls").length() == 0) continue;
            sessions += ses.length();
            calls += day.getJSONArray("calls").length();
            if (i == 0) for (int k = 0; k < ses.length(); k++) { JSONArray s = ses.getJSONArray(k); todayMin += (s.getLong(2) - s.getLong(1)) / 60000.0; }
            writes.put(Api.write(Api.itemName(ctx, "phone%3A" + date), day.toString(), "phone"));
        }
        Api.commit(ctx, writes);

        JSONObject prefs = null;
        try { prefs = Api.getD(ctx, Api.itemName(ctx, "meta%3Aprefs")); } catch (Exception ignored) { }
        SharedPreferences.Editor e = Api.p(ctx).edit();
        if (prefs != null) e.putBoolean("quietOn", prefs.optBoolean("quietOn", false)).putString("quietStart", prefs.optString("quietStart", "22:00")).putString("quietEnd", prefs.optString("quietEnd", "07:30"));
        e.apply();
        int alarms = Alarms.plan(ctx, Api.list(ctx, "task"), prefs);

        String sum = "Screen time today " + Alarms.mins((int) Math.round(todayMin)) + ", " + sessions + " app sessions, " + calls + " calls sent. " + alarms + " reminders set for the next 2 days.";
        Api.p(ctx).edit().putLong("lastSync", System.currentTimeMillis()).putString("lastSum", sum).remove("lastErr").apply();
        return sum;
    }

    static void schedule(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        ComponentName cn = new ComponentName(ctx, SyncJob.class);
        if (js.getPendingJob(JOB_PERIODIC) == null) {
            js.schedule(new JobInfo.Builder(JOB_PERIODIC, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        }
    }

    static void soon(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_NOW, new ComponentName(ctx, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60 * 1000L)
                .build());
    }
}
