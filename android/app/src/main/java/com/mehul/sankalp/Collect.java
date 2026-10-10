package com.mehul.sankalp;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.os.Build;
import android.os.Process;
import android.provider.CallLog;
import android.provider.CalendarContract;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Turns Android's own usage events (the data behind Digital Wellbeing) and the call log into one JSON object per day. */
final class Collect {
    private Collect() { }

    static boolean usageAllowed(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        int mode;
        if (Build.VERSION.SDK_INT >= 29) mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        else mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    static boolean calAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private static final Pattern JOIN = Pattern.compile("https://(teams\\.microsoft\\.com|[\\w.-]*zoom\\.us|meet\\.google\\.com|[\\w.-]*webex\\.com)/[^\\s\"<>]+");

    /** Events from every calendar on the phone (Google, and Outlook if it syncs to the phone calendar). */
    static JSONObject calendar(Context c, long from, long to) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray ev = new JSONArray();
        JSONArray cals = new JSONArray();
        java.util.Set<String> calSet = new java.util.LinkedHashSet<>();
        if (calAllowed(c)) {
            android.net.Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
            android.content.ContentUris.appendId(b, from);
            android.content.ContentUris.appendId(b, to);
            String[] cols = { CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                    CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.OWNER_ACCOUNT };
            Cursor cur = null;
            try {
                cur = c.getContentResolver().query(b.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC");
                int n = 0;
                while (cur != null && cur.moveToNext() && n < 600) {
                    String title = cur.getString(1);
                    String cal = cur.getString(5);
                    String owner = cur.getString(9);
                    if (owner != null && owner.contains("@") && (cal == null || !cal.contains(owner))) cal = (cal == null || cal.isEmpty() ? "Calendar" : cal) + " (" + owner + ")";
                    String txt = (cur.getString(7) == null ? "" : cur.getString(7)) + " " + (cur.getString(8) == null ? "" : cur.getString(8));
                    Matcher m = JOIN.matcher(txt);
                    String join = m.find() ? m.group() : "";
                    ev.put(new JSONArray().put(cur.getLong(0)).put(title == null ? "Busy" : title).put(cur.getLong(2)).put(cur.getLong(3)).put(cur.getInt(4) == 1).put(cal == null ? "" : cal).put(join).put(cur.getInt(6)));
                    if (cal != null) calSet.add(cal);
                    n++;
                }
            } finally { if (cur != null) cur.close(); }
            // Second pass: single events read straight from the Events table, in case the phone did not expand them into Instances.
            java.util.Set<String> have = new java.util.HashSet<>();
            for (int i = 0; i < ev.length(); i++) { JSONArray a = ev.getJSONArray(i); have.add(a.getLong(0) + "|" + a.getLong(2)); }
            String[] ec = { CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY, CalendarContract.Events.CALENDAR_DISPLAY_NAME, CalendarContract.Events.SELF_ATTENDEE_STATUS,
                    CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION, CalendarContract.Events.OWNER_ACCOUNT };
            try {
                cur = c.getContentResolver().query(CalendarContract.Events.CONTENT_URI, ec,
                        CalendarContract.Events.DTSTART + ">=? AND " + CalendarContract.Events.DTSTART + "<? AND " + CalendarContract.Events.DELETED + "=0",
                        new String[] { String.valueOf(from), String.valueOf(to) }, CalendarContract.Events.DTSTART + " ASC");
                while (cur != null && cur.moveToNext()) {
                    long id = cur.getLong(0), s0 = cur.getLong(2);
                    if (have.contains(id + "|" + s0)) continue;
                    long e0 = cur.isNull(3) ? s0 + 3600000L : cur.getLong(3);
                    String title = cur.getString(1), cal = cur.getString(5), owner = cur.getString(9);
                    if (owner != null && owner.contains("@") && (cal == null || !cal.contains(owner))) cal = (cal == null || cal.isEmpty() ? "Calendar" : cal) + " (" + owner + ")";
                    String txt = (cur.getString(7) == null ? "" : cur.getString(7)) + " " + (cur.getString(8) == null ? "" : cur.getString(8));
                    Matcher m = JOIN.matcher(txt);
                    ev.put(new JSONArray().put(id).put(title == null ? "Busy" : title).put(s0).put(e0).put(cur.getInt(4) == 1).put(cal == null ? "" : cal).put(m.find() ? m.group() : "").put(cur.getInt(6)).put(1));
                    if (cal != null) calSet.add(cal);
                }
            } catch (Exception ignored) {
            } finally { if (cur != null) cur.close(); }
            // Every calendar on the phone, with whether it is shown and synced, for the diagnostics list.
            String[] cc = { CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.VISIBLE, CalendarContract.Calendars.SYNC_EVENTS };
            JSONArray all = new JSONArray();
            try {
                cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, cc, null, null, null);
                while (cur != null && cur.moveToNext()) {
                    long cid = cur.getLong(0);
                    int cnt = 0;
                    Cursor k = null;
                    try {
                        k = c.getContentResolver().query(CalendarContract.Events.CONTENT_URI, new String[] { CalendarContract.Events._ID },
                                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events.DTSTART + ">=? AND " + CalendarContract.Events.DTSTART + "<? AND " + CalendarContract.Events.DELETED + "=0",
                                new String[] { String.valueOf(cid), String.valueOf(from), String.valueOf(to) }, null);
                        cnt = k == null ? 0 : k.getCount();
                    } catch (Exception ignored) { } finally { if (k != null) k.close(); }
                    all.put(new JSONArray().put(cur.getString(1)).put(cur.getString(2)).put(cur.getString(3)).put(cur.getInt(4)).put(cur.getInt(5)).put(cnt));
                }
            } catch (Exception ignored) {
            } finally { if (cur != null) cur.close(); }
            out.put("calInfo", all);
        }
        for (String x : calSet) cals.put(x);
        out.put("id", "all").put("at", System.currentTimeMillis()).put("ev", ev).put("cals", cals);
        return out;
    }

    static boolean callsAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    private static Set<String> skipSet(Context c) {
        Set<String> s = new HashSet<>();
        s.add(c.getPackageName());
        s.add("android");
        s.add("com.android.systemui");
        s.add("com.google.android.permissioncontroller");
        s.add("com.android.permissioncontroller");
        s.add("com.android.settings");
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo r : c.getPackageManager().queryIntentActivities(home, 0)) s.add(r.activityInfo.packageName);
        } catch (Exception ignored) { }
        return s;
    }

    private static final class Ses { String pkg; long s, e; Ses(String p, long a, long b) { pkg = p; s = a; e = b; } }

    static JSONObject day(Context c, String date, long d0, long d1) throws Exception {
        long now = System.currentTimeMillis();
        long end = Math.min(d1, now);
        JSONObject out = new JSONObject();
        out.put("id", date);
        out.put("at", now);
        JSONArray ses = new JSONArray();
        JSONObject apps = new JSONObject();
        int unlocks = 0;
        if (usageAllowed(c) && end > d0) {
            UsageStatsManager um = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents ev = um.queryEvents(d0, end);
            UsageEvents.Event e = new UsageEvents.Event();
            Set<String> skip = skipSet(c);
            List<Ses> list = new ArrayList<>();
            String cur = null;
            long st = 0;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                String pkg = e.getPackageName();
                long ts = e.getTimeStamp();
                if (t == 1) { // activity resumed / moved to foreground
                    if (cur != null && !cur.equals(pkg)) { add(list, skip, cur, st, ts); cur = null; }
                    if (cur == null) { cur = pkg; st = ts; }
                } else if (t == 2) { // paused / moved to background
                    if (pkg != null && pkg.equals(cur)) { add(list, skip, cur, st, ts); cur = null; }
                } else if (t == 16) { // screen off
                    if (cur != null) { add(list, skip, cur, st, ts); cur = null; }
                } else if (t == 18) { // keyguard hidden = unlock
                    unlocks++;
                }
            }
            if (cur != null) add(list, skip, cur, st, end);
            PackageManager pm = c.getPackageManager();
            Map<String, String> labels = new HashMap<>();
            int n = 0;
            for (Ses x : list) {
                if (x.e - x.s < 10000) continue;
                if (n++ > 1500) break;
                ses.put(new JSONArray().put(x.pkg).put(x.s).put(x.e));
                if (!labels.containsKey(x.pkg)) {
                    String label = x.pkg;
                    try { label = pm.getApplicationLabel(pm.getApplicationInfo(x.pkg, 0)).toString(); } catch (Exception ignored) { }
                    labels.put(x.pkg, label);
                    apps.put(x.pkg, label);
                }
            }
        }
        out.put("ses", ses);
        out.put("apps", apps);
        out.put("unlocks", unlocks);
        out.put("calls", calls(c, d0, d1));
        return out;
    }

    private static void add(List<Ses> list, Set<String> skip, String pkg, long s, long e) {
        if (pkg == null || skip.contains(pkg) || e - s < 3000) return;
        if (!list.isEmpty()) {
            Ses last = list.get(list.size() - 1);
            if (last.pkg.equals(pkg) && s - last.e < 30000) { last.e = Math.max(last.e, e); return; }
        }
        list.add(new Ses(pkg, s, e));
    }

    static JSONArray calls(Context c, long d0, long d1) {
        JSONArray out = new JSONArray();
        if (!callsAllowed(c)) return out;
        String[] cols = { CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER };
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols,
                    CallLog.Calls.DATE + ">=? AND " + CallLog.Calls.DATE + "<?",
                    new String[] { String.valueOf(d0), String.valueOf(d1) }, CallLog.Calls.DATE + " ASC");
            if (cur == null) return out;
            while (cur.moveToNext()) {
                long date = cur.getLong(0);
                long dur = cur.getLong(1);
                int type = cur.getInt(2);
                String name = cur.getString(3);
                String num = cur.getString(4);
                if (name == null || name.isEmpty()) name = num == null || num.length() < 4 ? "Unknown" : "…" + num.substring(num.length() - 4);
                out.put(new JSONArray().put(date).put(dur).put(type).put(name));
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }
}
