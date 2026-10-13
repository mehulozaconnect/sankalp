package com.mehul.sankalp;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** The reminder card that rises from the bottom of the screen when a reminder rings while the phone is locked or idle. */
public class ReminderActivity extends Activity {
    private JSONObject a;
    private boolean dark;
    private int ink, muted, card, chip, line;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); load(i); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void load(Intent i) {
        try { a = new JSONObject(i.getStringExtra("a")); } catch (Exception e) { finish(); return; }
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF1F3F6 : 0xFF16191F;
        muted = dark ? 0xFF9AA1AD : 0xFF5D6470;
        card = dark ? 0xFF22262D : 0xFFFFFFFF;
        chip = dark ? 0xFF2E333B : 0xFFF2F4F7;
        line = dark ? 0xFF3A404A : 0xFFE3E6EB;
        build();
        if (i.getBooleanExtra("resched", false) && !a.optBoolean("meet")) getWindow().getDecorView().post(this::reschedule);
    }

    private static int prioColor(int p) {
        return p == 1 ? 0xFFE5484D : p == 2 ? 0xFFF2A33A : p == 3 ? 0xFF2F7BEA : 0xFF8A919C;
    }

    private GradientDrawable round(int color, int r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g; }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private TextView pill(String s, int bg, int fg, View.OnClickListener l) {
        TextView t = text(s, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(bg, 16));
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setOnClickListener(l);
        return t;
    }

    private void build() {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(0x66000000));
        int prio = a.optInt("prio", 4);
        int pc = a.optBoolean("meet") ? 0xFF8B5CF6 : prioColor(prio);

        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> finish());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(card, 28));
        box.setPadding(dp(22), dp(18), dp(22), dp(20));
        box.setClickable(true);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        blp.setMargins(dp(12), 0, dp(12), dp(16));
        root.addView(box, blp);

        // Top row: Sankalp · time, and close.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String when = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(a.optLong("at", System.currentTimeMillis()))).toLowerCase(Locale.US);
        TextView head = text("Sankalp  ·  " + when, 13, muted, true);
        top.addView(head, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView x = text("✕", 18, muted, false);
        x.setPadding(dp(10), dp(4), dp(2), dp(4));
        x.setContentDescription("Close");
        x.setOnClickListener(v -> finish());
        top.addView(x);
        box.addView(top);

        // Title block with a priority stripe.
        LinearLayout mid = new LinearLayout(this);
        mid.setPadding(0, dp(14), 0, dp(4));
        View stripe = new View(this);
        stripe.setBackground(round(pc, 3));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT);
        slp.setMargins(0, dp(2), dp(14), dp(2));
        mid.addView(stripe, slp);
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        if (!a.optBoolean("meet") && prio < 4) {
            TextView pl = text("P" + prio + (a.has("sub") ? "  ·  Subtask" : ""), 12, pc, true);
            tx.addView(pl);
        }
        TextView title = text(a.optString("title", "Reminder"), 22, ink, true);
        title.setMaxLines(3); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setLineSpacing(0, 1.1f);
        title.setPadding(0, dp(2), 0, 0);
        tx.addView(title);
        int n = a.optInt("n", 0);
        String body = n > 0 ? "Still not done" : a.optString("body", "");
        if (!body.isEmpty()) { TextView bt = text(body, 14, muted, false); bt.setPadding(0, dp(4), 0, 0); tx.addView(bt); }
        mid.addView(tx, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        mid.setOnClickListener(v -> { open(); });
        box.addView(mid);

        if (a.optBoolean("meet")) {
            String join = a.optString("join", "");
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(18), 0, 0);
            if (!join.isEmpty()) row.addView(pill("Join", 0xFF8B5CF6, Color.WHITE, v -> { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(join))); dismiss(); }), weight(52, 0));
            row.addView(pill("OK", chip, ink, v -> dismiss()), weight(52, join.isEmpty() ? 0 : 10));
            box.addView(row);
        } else {
            TextView sl = text("Snooze", 12, muted, true);
            sl.setPadding(0, dp(18), 0, dp(8));
            box.addView(sl);
            LinearLayout chips = new LinearLayout(this);
            int[] mins = { 10, 30, 60 };
            for (int k = 0; k < mins.length; k++) {
                final int m = mins[k];
                chips.addView(pill(m < 60 ? m + " min" : "1 hour", chip, ink, v -> snooze(m)), weight(44, k == 0 ? 0 : 8));
            }
            box.addView(chips);
            View sep = new View(this); sep.setBackgroundColor(line);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
            sp.setMargins(0, dp(14), 0, dp(14));
            box.addView(sep, sp);
            LinearLayout last = new LinearLayout(this);
            TextView rs = pill("Reschedule", chip, ink, v -> reschedule());
            rs.setTextSize(16);
            last.addView(rs, weight(54, 0));
            TextView done = pill("✓  Mark done", 0xFF1F8A3B, Color.WHITE, v -> done());
            done.setTextSize(16);
            last.addView(done, weight(54, 10));
            box.addView(last);
        }
        setContentView(root);
        box.setTranslationY(dp(220));
        box.setAlpha(0f);
        box.animate().translationY(0).alpha(1f).setDuration(260).start();
    }

    private LinearLayout.LayoutParams weight(int h, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(h), 1);
        p.setMargins(dp(left), 0, 0, 0);
        return p;
    }

    private void cancelNotif() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(Alarms.notifId(a));
    }

    private void dismiss() { cancelNotif(); finish(); }

    private void unlockThen(Runnable r) {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissSucceeded() { r.run(); }
            });
        } else r.run();
    }

    private void open() {
        if (a.optBoolean("test")) { dismiss(); return; }
        unlockThen(() -> {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Alarms.SITE + "?open=" + Uri.encode(a.optString("tid")))));
            dismiss();
        });
    }

    private void snooze(int mins) {
        if (a.optBoolean("test")) { android.widget.Toast.makeText(this, "Works. Real reminders snooze for " + mins + " min.", android.widget.Toast.LENGTH_SHORT).show(); dismiss(); return; }
        Intent i = new Intent(this, ActionReceiver.class).setAction("snooze").putExtra("a", a.toString()).putExtra("mins", mins);
        sendBroadcast(i);
        dismiss();
    }

    /** Pick a new date, then a new time; the task moves there and its reminder follows. */
    private void reschedule() {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        long at = a.optLong("at", System.currentTimeMillis());
        if (at > System.currentTimeMillis()) c.setTimeInMillis(at);
        int theme = dark ? android.R.style.Theme_Material_Dialog : android.R.style.Theme_Material_Light_Dialog;
        android.app.DatePickerDialog dd = new android.app.DatePickerDialog(this, theme, (dp, y, m, d) -> {
            final java.util.Calendar pick = java.util.Calendar.getInstance();
            pick.set(y, m, d);
            android.app.TimePickerDialog td = new android.app.TimePickerDialog(this, theme, (tp, hh, mm) -> {
                pick.set(java.util.Calendar.HOUR_OF_DAY, hh); pick.set(java.util.Calendar.MINUTE, mm); pick.set(java.util.Calendar.SECOND, 0);
                saveNewTime(pick);
            }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this));
            td.setTitle("New time");
            td.show();
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
        dd.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dd.show();
    }

    private void saveNewTime(java.util.Calendar pick) {
        final String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(pick.getTime());
        final String time = new SimpleDateFormat("HH:mm", Locale.US).format(pick.getTime());
        final String label = new SimpleDateFormat("EEE d MMM, h:mm a", Locale.US).format(pick.getTime());
        if (a.optBoolean("test")) { android.widget.Toast.makeText(this, "Works. Real tasks move to " + label + ".", android.widget.Toast.LENGTH_SHORT).show(); dismiss(); return; }
        final android.content.Context ctx = getApplicationContext();
        final JSONObject ja = a;
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        Alarms.cancelNag(ctx, ja);
        dismiss();
        new Thread(() -> {
            try {
                String name = ja.optString("name");
                JSONObject t = Api.getD(ctx, name);
                if (t == null) throw new IllegalStateException("task not found");
                if (ja.has("sub")) {
                    org.json.JSONArray subs = t.optJSONArray("subs");
                    if (subs != null) for (int k = 0; k < subs.length(); k++) {
                        JSONObject c = subs.getJSONObject(k);
                        if (ja.optString("sub").equals(c.optString("id"))) { c.put("at", date + "T" + time); c.put("nagAt", 0); c.put("snoozeUntil", 0); }
                    }
                } else {
                    t.put("date", date).put("time", time).put("someday", false).put("snoozeUntil", 0).put("nagAt", 0).put("firedKeys", new org.json.JSONArray());
                    org.json.JSONArray rem = t.optJSONArray("reminders");
                    if (rem == null || rem.length() == 0) t.put("reminders", new org.json.JSONArray().put(0));
                }
                Api.commit(ctx, new org.json.JSONArray().put(Api.write(name, t.toString(), "task")));
                try { Sync.replan(ctx); } catch (Exception ignored) { }
                ui.post(() -> android.widget.Toast.makeText(ctx, "Moved to " + label, android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Api.p(ctx).edit().putString("lastErr", "Could not reschedule: " + e.getMessage()).apply();
                ui.post(() -> android.widget.Toast.makeText(ctx, "Could not reschedule. Check internet and try again.", android.widget.Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void done() {
        if (a.optBoolean("test")) { android.widget.Toast.makeText(this, "Works. Real tasks are marked done in Sankalp.", android.widget.Toast.LENGTH_SHORT).show(); dismiss(); return; }
        if (a.optBoolean("rep") && !a.has("sub")) {
            // Repeating tasks are completed in Sankalp so the next one is created correctly.
            unlockThen(() -> {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Alarms.SITE + "?done=" + Uri.encode(a.optString("tid")))));
                dismiss();
            });
            return;
        }
        sendBroadcast(new Intent(this, ActionReceiver.class).setAction("done").putExtra("a", a.toString()));
        dismiss();
    }

    static PendingIntent pending(android.content.Context ctx, JSONObject a, int req) {
        Intent i = new Intent(ctx, ReminderActivity.class)
                .setData(Uri.parse("sankalp://card/" + Uri.encode(a.optString("id"))))
                .putExtra("a", a.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
