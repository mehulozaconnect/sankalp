package com.mehul.sankalp;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: link to your Sankalp account, grant permissions, see the last sync. */
public class MainActivity extends Activity {
    private static final int BLUE = 0xFF2F7BEA, INK = 0xFF16191F, MUTED = 0xFF5D6470, SURFACE = 0xFFF2F4F7, GREEN = 0xFF1F8A3B, RED = 0xFFD9483B;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Alarms.channel(this);
        handleLink(getIntent());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 3);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLink(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
        if (Api.linked(this)) { Coach.schedule(this); Sync.schedule(this); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        build();
        if (Api.linked(this)) Sync.soon(this);
    }

    private void handleLink(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u == null || !"sankalp".equals(u.getScheme()) || !"link".equals(u.getHost())) return;
        String rt = u.getQueryParameter("r"), key = u.getQueryParameter("k"), proj = u.getQueryParameter("p"), uid = u.getQueryParameter("u");
        if (rt == null || rt.isEmpty() || key == null || proj == null || uid == null) { Toast.makeText(this, "Link incomplete. Sign in to Sync in Sankalp first.", Toast.LENGTH_LONG).show(); return; }
        Api.p(this).edit().putString("rt", rt).putString("key", key).putString("proj", proj).putString("uid", uid)
                .putString("email", u.getQueryParameter("n")).remove("idt").remove("exp").remove("lastErr").apply();
        Toast.makeText(this, "Linked to Sankalp", Toast.LENGTH_SHORT).show();
        Sync.schedule(this);
        syncNow(7);
    }

    private void syncNow(final int days) {
        Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try { msg = Sync.run(getApplicationContext(), days); }
            catch (Exception e) { msg = "Sync failed: " + e.getMessage(); Api.p(this).edit().putString("lastErr", e.getMessage()).apply(); }
            final String m = msg;
            runOnUiThread(() -> { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); build(); });
        }).start();
    }

    private void testCard() {
        try {
            org.json.JSONObject a = new org.json.JSONObject().put("id", "test|card").put("tid", "test").put("title", "Test reminder from Sankalp")
                    .put("body", "Lock your phone now. This card should appear in 10 seconds.").put("prio", 1).put("nag", 0).put("n", 0)
                    .put("at", System.currentTimeMillis() + 10000L).put("test", true);
            Alarms.schedule(this, a);
            Toast.makeText(this, "Lock your phone now. The reminder rings in 10 seconds.", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Could not set test: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : INK);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(primary ? BLUE : SURFACE);
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void row(String title, boolean ok, String detail, String btn, View.OnClickListener l) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(SURFACE);
        card.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);
        card.addView(text((ok ? "✓  " : "○  ") + title, 16, ok ? GREEN : INK, true));
        TextView d = text(detail, 13, MUTED, false);
        d.setPadding(0, dp(4), 0, 0);
        card.addView(d);
        if (!ok && btn != null) card.addView(button(btn, true, l));
        root.addView(card);
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(40));
        sv.addView(root);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        head.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView h = text("Sankalp Phone", 26, INK, true);
        h.setPadding(dp(12), 0, 0, 0);
        head.addView(h);
        root.addView(head);
        TextView sub = text("Adds your screen time and calls to Day review, and rings task reminders even when Sankalp is closed.", 14, MUTED, false);
        sub.setPadding(0, dp(10), 0, dp(6));
        root.addView(sub);

        SharedPreferences p = Api.p(this);
        boolean linked = Api.linked(this);
        row("Linked to your Sankalp", linked, linked ? "Signed in as " + p.getString("email", "your Gmail") : "Open Sankalp on this phone, Settings, \"Android app\", tap Link phone app.", "Open Sankalp",
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Alarms.SITE + "#settings/phoneapp"))));
        row("Screen time (Usage access)", Collect.usageAllowed(this), "Find \"Sankalp Phone\" in the list and switch it on. This is the same data Digital Wellbeing uses.", "Allow usage access",
                v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
        row("Calendar", Collect.calAllowed(this), "Shows your phone's calendar meetings (Google, and Outlook if it syncs to the phone) in Sankalp, with a reminder before each.", "Allow calendar",
                v -> requestPermissions(new String[] { Manifest.permission.READ_CALENDAR }, 4));
        row("Calls (Call log)", Collect.callsAllowed(this), "Adds your phone calls as Client calls, with time and duration.", "Allow call log",
                v -> requestPermissions(new String[] { Manifest.permission.READ_CALL_LOG }, 2));
        boolean notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        row("Reminder notifications", notif, "Needed to show task reminders.", "Allow notifications",
                v -> requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 3));
        Alarms.channel(this);
        android.app.NotificationChannel rch = ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).getNotificationChannel(Alarms.CH);
        boolean snd = rch != null && rch.getSound() != null && rch.getImportance() >= android.app.NotificationManager.IMPORTANCE_DEFAULT;
        row("Reminder sound", snd, "Reminders ring on your alarm volume. Tap to pick a different tone or check the sound is on.", "Sound settings",
                v -> startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, Alarms.CH)));
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        row("On-time alarms", exact, "Lets reminders ring at the exact minute.", "Allow alarms",
                v -> startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        row("Reminder card on lock screen", Alarms.cardOn(this), "Shows a big card with Done and Snooze when a reminder rings while the phone is locked.", "Allow",
                v -> startActivity(new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", Uri.parse("package:" + getPackageName()))));
        row("Card on any phone (Display over other apps)", Settings.canDrawOverlays(this), "Some phones (Realme, OPPO, Xiaomi, Vivo) hide the card unless this is on. Find Sankalp Phone and switch it on.", "Allow",
                v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batt = pm.isIgnoringBatteryOptimizations(getPackageName());
        row("Not stopped by battery saver", batt, "So syncing and reminders keep working in the background. Uses very little battery.", "Allow",
                v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));

        long last = p.getLong("lastSync", 0);
        TextView st = text(last > 0 ? "Last sync " + DateUtils.getRelativeTimeSpanString(last) + ". " + p.getString("lastSum", "") : "Not synced yet.", 14, INK, false);
        st.setPadding(0, dp(18), 0, 0);
        root.addView(st);
        String err = p.getString("lastErr", null);
        if (err != null) { TextView e = text("Last problem: " + err, 13, RED, false); e.setPadding(0, dp(6), 0, 0); root.addView(e); }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.VERTICAL);
        btns.setPadding(0, dp(8), 0, 0);
        root.addView(btns);
        if (linked) root.addView(button("Sync now", true, v -> syncNow(2)));
        root.addView(button("Test reminder card (rings in 10 sec)", false, v -> testCard()));
        root.addView(button("Open Sankalp", false, v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Alarms.SITE + "#review/today")))));
        TextView foot = text("Your data goes only to your own Firebase project. Syncs when your calendar changes, and every 15 minutes.", 12, MUTED, false);
        foot.setPadding(0, dp(18), 0, 0);
        root.addView(foot);
        setContentView(sv);
    }
}
