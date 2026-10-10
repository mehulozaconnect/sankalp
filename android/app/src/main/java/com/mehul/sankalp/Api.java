package com.mehul.sankalp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Talks to Firebase Auth (token refresh) and Firestore REST, using the user's own project. */
final class Api {
    private Api() { }

    static final String FS = "https://firestore.googleapis.com/v1/";

    static SharedPreferences p(Context c) { return c.getSharedPreferences("sankalp", Context.MODE_PRIVATE); }

    static boolean linked(Context c) { return p(c).getString("rt", null) != null; }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    static String http(String method, String url, String body, String ctype, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes(StandardCharsets.UTF_8));
            o.close();
        }
        int code = c.getResponseCode();
        String r = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + ": " + (r.length() > 300 ? r.substring(0, 300) : r));
        return r;
    }

    /** Returns a valid Firebase ID token, refreshing it with the stored refresh token when needed. */
    static synchronized String token(Context ctx) throws Exception {
        SharedPreferences p = p(ctx);
        String t = p.getString("idt", null);
        long exp = p.getLong("exp", 0);
        if (t != null && System.currentTimeMillis() < exp - 120000) return t;
        String rt = p.getString("rt", null), key = p.getString("key", null);
        if (rt == null || key == null) throw new IllegalStateException("Not linked yet. Open Sankalp, Settings, Link phone app.");
        JSONObject j = new JSONObject(http("POST", "https://securetoken.googleapis.com/v1/token?key=" + enc(key),
                "grant_type=refresh_token&refresh_token=" + enc(rt), "application/x-www-form-urlencoded", null));
        t = j.getString("id_token");
        long ttl = 3600;
        try { ttl = Long.parseLong(j.optString("expires_in", "3600")); } catch (Exception ignored) { }
        p.edit().putString("idt", t).putLong("exp", System.currentTimeMillis() + ttl * 1000)
                .putString("rt", j.optString("refresh_token", rt)).apply();
        return t;
    }

    static String project(Context c) { return p(c).getString("proj", ""); }

    static String dbRoot(Context c) { return "projects/" + project(c) + "/databases/(default)/documents"; }

    /** Full resource name of a document in users/{uid}/items. id is already in the app's encoded form (e.g. task%3Aabc). */
    static String itemName(Context c, String id) { return dbRoot(c) + "/users/" + p(c).getString("uid", "") + "/items/" + id; }

    static JSONObject fields(String d, String kind) throws Exception {
        JSONObject f = new JSONObject();
        f.put("d", new JSONObject().put("stringValue", d));
        f.put("u", new JSONObject().put("integerValue", String.valueOf(System.currentTimeMillis())));
        f.put("x", new JSONObject().put("booleanValue", false));
        f.put("k", new JSONObject().put("stringValue", kind));
        return f;
    }

    static JSONObject write(String name, String d, String kind) throws Exception {
        return new JSONObject().put("update", new JSONObject().put("name", name).put("fields", fields(d, kind)));
    }

    static void commit(Context ctx, JSONArray writes) throws Exception {
        if (writes.length() == 0) return;
        String url = FS + "projects/" + project(ctx) + "/databases/(default)/documents:commit";
        http("POST", url, new JSONObject().put("writes", writes).toString(), "application/json", token(ctx));
    }

    /** Reads one item and returns its parsed "d" JSON, or null if missing or deleted. */
    static JSONObject getD(Context ctx, String name) throws Exception {
        String url = FS + "projects/" + project(ctx) + "/databases/(default)/documents:batchGet";
        String r = http("POST", url, new JSONObject().put("documents", new JSONArray().put(name)).toString(), "application/json", token(ctx));
        JSONArray a = new JSONArray(r);
        for (int i = 0; i < a.length(); i++) {
            JSONObject found = a.getJSONObject(i).optJSONObject("found");
            if (found == null) continue;
            JSONObject f = found.optJSONObject("fields");
            if (f == null) continue;
            JSONObject x = f.optJSONObject("x");
            if (x != null && x.optBoolean("booleanValue", false)) return null;
            JSONObject d = f.optJSONObject("d");
            if (d == null) return null;
            String s = d.optString("stringValue", "");
            if (s.isEmpty()) return null;
            return new JSONObject(s);
        }
        return null;
    }

    /** All items of one kind (e.g. "task"), as objects {name, d}. */
    static JSONArray list(Context ctx, String kind) throws Exception {
        String url = FS + dbRoot(ctx) + "/users/" + p(ctx).getString("uid", "") + ":runQuery";
        JSONObject q = new JSONObject().put("structuredQuery", new JSONObject()
                .put("from", new JSONArray().put(new JSONObject().put("collectionId", "items")))
                .put("where", new JSONObject().put("fieldFilter", new JSONObject()
                        .put("field", new JSONObject().put("fieldPath", "k"))
                        .put("op", "EQUAL")
                        .put("value", new JSONObject().put("stringValue", kind)))));
        JSONArray a = new JSONArray(http("POST", url, q.toString(), "application/json", token(ctx)));
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject doc = a.getJSONObject(i).optJSONObject("document");
            if (doc == null) continue;
            JSONObject f = doc.optJSONObject("fields");
            if (f == null) continue;
            JSONObject x = f.optJSONObject("x");
            if (x != null && x.optBoolean("booleanValue", false)) continue;
            JSONObject d = f.optJSONObject("d");
            if (d == null) continue;
            try { out.put(new JSONObject().put("name", doc.getString("name")).put("d", new JSONObject(d.getString("stringValue")))); }
            catch (Exception ignored) { }
        }
        return out;
    }
}
