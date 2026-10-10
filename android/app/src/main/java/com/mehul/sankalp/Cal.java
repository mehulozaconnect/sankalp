package com.mehul.sankalp;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the phone's own calendar (Google Calendar, and Outlook if its calendar sync is on). */
final class Cal {
    private Cal() { }

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private static final Pattern JOIN = Pattern.compile("https://(teams\\.microsoft\\.com|teams\\.live\\.com|[\\w.-]*zoom\\.us|meet\\.google\\.com)/[^\\s\"<>)]+");

    /** Events from yesterday to 30 days ahead: [id, title, start, end, allDay, calendar, join]. */
    static JSONArray events(Context c) {
        JSONArray out = new JSONArray();
        if (!allowed(c)) return out;
        long now = System.currentTimeMillis();
        long from = now - 36L * 3600000L, to = now + 31L * 86400000L;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] cols = { CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DESCRIPTION, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
                CalendarContract.Instances.VISIBLE };
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(b.build(), cols, null, null, CalendarContract.Instances.BEGIN + " ASC");
            if (cur == null) return out;
            int n = 0;
            while (cur.moveToNext() && n < 600) {
                if (cur.getInt(9) == 0) continue; // calendar hidden on the phone
                if (cur.getInt(8) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue;
                String title = cur.getString(1);
                if (title == null || title.isEmpty()) title = "Busy";
                String text = (cur.getString(6) == null ? "" : cur.getString(6)) + " " + (cur.getString(7) == null ? "" : cur.getString(7));
                Matcher m = JOIN.matcher(text);
                String join = m.find() ? m.group() : "";
                long s = cur.getLong(2), e = cur.getLong(3);
                out.put(new JSONArray().put(cur.getLong(0) + "-" + s).put(title).put(s).put(e).put(cur.getInt(4) == 1).put(cur.getString(5) == null ? "" : cur.getString(5)).put(join));
                n++;
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return out;
    }

    static JSONObject doc(Context c) throws Exception {
        return new JSONObject().put("at", System.currentTimeMillis()).put("ev", events(c));
    }
}
