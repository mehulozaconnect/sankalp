package com.mehul.sankalp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Gentle pushes: live app nudges, social limit, P1 check, morning plan, evening summary, Sunday report. Runs every 5 minutes, offline. */
final class Coach {
    private Coach() { }

    static final String CH = "coach";
    static final Pattern SOCIAL = Pattern.compile("instagram|facebook\\.katana|facebook\\.lite|youtube|snapchat|twitter|com\\.x\\.|reddit|sharechat|moj|josh|threads|pinterest|tiktok|netflix|hotstar|primevideo|amazon\\.avod|jiocinema|zee5|sonyliv|mxplayer|dailyhunt|inshorts", Pattern.CASE_INSENSITIVE);
    static final Pattern GAMES = Pattern.compile("game|pubg|bgmi|freefire|ludo|candy|clash|rummy|dream11|mpl", Pattern.CASE_INSENSITIVE);

    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR_OF_DAY);
        long step = (h >= 7 && h < 23) ? 5 * 60000L : 20 * 60000L;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 77, new Intent(ctx, CoachReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + step, pi);
    }

    static JSONObject obj(SharedPreferences p, String k) { try { return new JSONObject(p.getString(k, "{}")); } catch (Exception e) { return new JSONObject(); } }

    static String hm() { Calendar c = Calendar.getInstance(); return String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)); }

    /** True if this app counts as social media or games, using the categories you set in Day review. */
    static boolean distracting(String pkg, JSONObject appmap) {
        String m = appmap.optString(pkg, "");
        if (!m.isEmpty()) return m.equals("social") || m.equals("idle");
        return SOCIAL.matcher(pkg).find() || GAMES.matcher(pkg).find();
    }

    static String label(Context ctx, String pkg) {
        try { PackageManager pm = ctx.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception e) { return pkg; }
    }

    static void tick(Context ctx) {
        SharedPreferences p = Api.p(ctx);
        if (!Api.linked(ctx)) return;
        JSONObject c = obj(p, "coach"), appmap = obj(p, "appmap"), brief = obj(p, "brief");
        String now = hm(), td = Sync.day(System.currentTimeMillis());
        String ws = p.getString("workStart", "09:30"), we = p.getString("shutdown", "19:00"), morning = p.getString("morning", "08:30");
        Calendar cal = Calendar.getInstance();
        boolean workday = cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY;
        boolean work = workday && now.compareTo(ws) >= 0 && now.compareTo(we) < 0;
        if (Alarms.quiet(ctx)) return;
        SharedPreferences.Editor e = p.edit();
        try {
            // 1. Live nudge: one distracting app used without a break.
            if (work && c.optBoolean("nudge", true) && Collect.usageAllowed(ctx) && System.currentTimeMillis() > p.getLong("ngSnooze", 0)) {
                long[] run = new long[1];
                String pkg = current(ctx, run);
                int nm = Math.max(5, c.optInt("nudgeMin", 15));
                if (pkg != null && distracting(pkg, appmap)) {
                    long mins = (System.currentTimeMillis() - run[0]) / 60000L;
                    long step = mins / nm;
                    String key = "ng_" + pkg + "_" + run[0] + "_" + step;
                    if (step >= 1 && !p.getBoolean(key, false)) {
                        e.putBoolean(key, true);
                        String back = brief.optString("topTitle", "");
                        notify(ctx, 101, mins + " min on " + label(ctx, pkg),
                                back.isEmpty() ? "Time to get back to your day." : "Back to: " + back + "?",
                                Alarms.SITE + (back.isEmpty() ? "#today" : "?open=" + Uri.encode(brief.optString("topId"))), "Back to work", "snooze5");
                    }
                }
            }
            // 2. Daily social media limit.
            int limit = c.optInt("limit", 60);
            if (work && limit > 0 && Collect.usageAllowed(ctx) && !p.getBoolean("lim100_" + td, false)) {
                double soc = socialToday(ctx, appmap);
                if (soc >= limit) { e.putBoolean("lim100_" + td, true).putBoolean("lim80_" + td, true); notify(ctx, 102, "Social media limit reached", Math.round(soc) + " min today, your limit is " + limit + ". Put the phone face down for the next hour.", Alarms.SITE + "#review/today", null, null); }
                else if (soc >= limit * 0.8 && !p.getBoolean("lim80_" + td, false)) { e.putBoolean("lim80_" + td, true); notify(ctx, 102, "Almost at your social media limit", Math.round(soc) + " of " + limit + " min used today.", Alarms.SITE + "#review/today", null, null); }
            }
            // 3. P1 check.
            String p1At = c.optString("p1At", "11:30");
            if (c.optBoolean("p1", true) && workday && now.compareTo(p1At) >= 0 && now.compareTo(we) < 0 && !p.getBoolean("p1_" + td, false) && td.equals(brief.optString("date")) && brief.optInt("topP", 9) == 1) {
                e.putBoolean("p1_" + td, true);
                notify(ctx, 103, "P1 still open", brief.optString("topTitle") + ". Start a 25-minute focus on it now?", Alarms.SITE + "?focus=" + Uri.encode(brief.optString("topId")), "Start focus", null);
            }
            // 4. Morning plan.
            if (c.optBoolean("morn", true) && now.compareTo(morning) >= 0 && now.compareTo("12:00") < 0 && !p.getBoolean("mo_" + td, false)) {
                e.putBoolean("mo_" + td, true);
                JSONObject cb = obj(p, "coachBrief");
                StringBuilder sb = new StringBuilder();
                if (!brief.optString("topTitle").isEmpty() && td.equals(brief.optString("date"))) sb.append("Start with: ").append(brief.optString("topTitle")).append(". ");
                if (!cb.optString("best").isEmpty()) sb.append("Your best hours: ").append(cb.optString("best")).append(". ");
                if (!cb.optString("ySoc").isEmpty()) sb.append("Yesterday: ").append(cb.optString("ySoc")).append(" on social media. ");
                if (!cb.optString("leak").isEmpty()) sb.append("Watch ").append(cb.optString("leak")).append(".");
                notify(ctx, 104, "Good morning", sb.length() == 0 ? "Open Sankalp to plan your day." : sb.toString().trim(), Alarms.SITE + "#today", null, null);
            }
            // 5. Evening summary.
            if (c.optBoolean("eve", true) && now.compareTo(we) >= 0 && now.compareTo("23:00") < 0 && !p.getBoolean("ev_" + td, false)) {
                e.putBoolean("ev_" + td, true);
                StringBuilder sb = new StringBuilder();
                if (td.equals(brief.optString("date"))) {
                    sb.append(brief.optInt("doneToday")).append(" tasks done");
                    if (brief.optInt("p1") > 0) sb.append(", P1 ").append(brief.optInt("p1d")).append(" of ").append(brief.optInt("p1"));
                    sb.append(". ");
                }
                if (Collect.usageAllowed(ctx)) { String[] top = new String[1]; double tot = screenToday(ctx, appmap, top); sb.append("Phone ").append(Alarms.mins((int) Math.round(tot))); if (top[0] != null) sb.append(", most on ").append(top[0]); sb.append(". "); }
                sb.append("Tap for your review.");
                notify(ctx, 105, "Your day", sb.toString(), Alarms.SITE + "#review/today", null, null);
            }
            // 6. Sunday weekly report.
            if (c.optBoolean("week", true) && cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY && now.compareTo("19:00") >= 0 && now.compareTo("22:00") < 0 && !p.getBoolean("wk_" + td, false)) {
                e.putBoolean("wk_" + td, true);
                JSONObject cb = obj(p, "coachBrief");
                String fix = cb.optString("fix", "");
                notify(ctx, 106, "Your week", brief.optInt("doneWeek") + " tasks done this week." + (fix.isEmpty() ? "" : " To fix next week: " + fix + ".") + " Tap for your weekly review.", Alarms.SITE + "#review/week", null, null);
            }
        } catch (Exception ignored) {
        } finally {
            e.apply();
        }
    }

    /** The app in front right now and when this run on it started (short breaks under 30 s are ignored). */
    static String current(Context ctx, long[] runStart) {
        UsageStatsManager um = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        UsageEvents ev = um.queryEvents(now - 3 * 3600000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String cur = null, prevPkg = null; long curStart = 0, prevEnd = 0, prevRun = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType(); String pkg = e.getPackageName(); long ts = e.getTimeStamp();
            if (t == 1 && pkg != null) {
                if (cur != null && !cur.equals(pkg)) { prevPkg = cur; prevEnd = ts; prevRun = curStart; cur = null; }
                if (cur == null) { cur = pkg; curStart = (pkg.equals(prevPkg) && ts - prevEnd < 30000) ? prevRun : ts; }
            } else if (t == 2 && pkg != null && pkg.equals(cur)) { prevPkg = cur; prevEnd = ts; prevRun = curStart; cur = null; }
            else if (t == 16 && cur != null) { prevPkg = null; cur = null; }
        }
        if (cur == null) return null;
        runStart[0] = curStart;
        return cur;
    }

    static double socialToday(Context ctx, JSONObject appmap) throws Exception {
        long d0 = Sync.midnight(0);
        JSONArray ses = Collect.day(ctx, Sync.day(d0), d0, Sync.midnight(1)).getJSONArray("ses");
        double m = 0;
        for (int i = 0; i < ses.length(); i++) { JSONArray s = ses.getJSONArray(i); if (distracting(s.getString(0), appmap)) m += (s.getLong(2) - s.getLong(1)) / 60000.0; }
        return m;
    }

    static double screenToday(Context ctx, JSONObject appmap, String[] topOut) throws Exception {
        long d0 = Sync.midnight(0);
        JSONObject dj = Collect.day(ctx, Sync.day(d0), d0, Sync.midnight(1));
        JSONArray ses = dj.getJSONArray("ses"); JSONObject apps = dj.getJSONObject("apps");
        Map<String, Double> per = new HashMap<>(); double tot = 0;
        for (int i = 0; i < ses.length(); i++) { JSONArray s = ses.getJSONArray(i); double m = (s.getLong(2) - s.getLong(1)) / 60000.0; tot += m; per.merge(s.getString(0), m, Double::sum); }
        String best = null; double bm = 0;
        for (Map.Entry<String, Double> x : per.entrySet()) if (x.getValue() > bm) { bm = x.getValue(); best = x.getKey(); }
        if (best != null) topOut[0] = apps.optString(best, best) + " " + Alarms.mins((int) Math.round(bm));
        return tot;
    }

    static void notify(Context ctx, int id, String title, String text, String url, String openLabel, String extra) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(new NotificationChannel(CH, "Coach", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(ctx, id, new Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Icon ic = Icon.createWithResource(ctx, R.drawable.ic_stat);
        Notification.Builder b = new Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setColor(0xFF2F7BEA)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true);
        if (openLabel != null) b.addAction(new Notification.Action.Builder(ic, openLabel, open).build());
        if ("snooze5".equals(extra)) {
            PendingIntent sn = PendingIntent.getBroadcast(ctx, id + 1000, new Intent(ctx, CoachReceiver.class).setAction("snooze5"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(ic, "5 more min", sn).build());
        }
        try { nm.notify(id, b.build()); } catch (SecurityException ignored) { }
    }
}
