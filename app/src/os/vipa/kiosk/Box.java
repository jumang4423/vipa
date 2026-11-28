package os.vipa.kiosk;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The outbox, in one place, so the page's waiter and the alarm fallback cannot drift apart.
 *
 * The connection is deliberately left open (no disconnect on success): the phone holds one
 * request at a time, and reusing the socket means no TLS handshake per wake-up.
 */
final class Box {

    private static final String TAG = "VIPA";

    /** The request in flight, so a network change can cancel it instead of waiting it out. */
    private static volatile HttpURLConnection current = null;

    private Box() {
    }

    static void abort() {
        HttpURLConnection c = current;
        if (c == null) return;
        try {
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }

    /** GET path and parse it. Returns null on a non-200. Throws on network trouble. */
    static JSONObject get(String path, int connectMs, int readMs) throws Exception {
        return getAt(MainActivity.SERVER, path, connectMs, readMs);
    }

    static JSONObject getAt(String base, String path, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            Tls.relax(c);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setUseCaches(false);
            c.setRequestProperty("Connection", "keep-alive");
            current = c;
            int code = c.getResponseCode();
            if (code != 200) {
                c.disconnect();
                return null;
            }
            InputStream in = c.getInputStream();
            String body = read(in);          // closes the stream, keeps the socket pooled
            return new JSONObject(body);
        } catch (Exception e) {
            if (c != null) c.disconnect();
            throw e;
        } finally {
            current = null;
        }
    }

    /** Apply one response: advance the cursor, notify if nobody is looking. */
    static void consume(Context ctx, JSONObject o) {
        if (o == null) return;
        try {
            SharedPreferences sp = ctx.getSharedPreferences("vipa", 0);
            int last = sp.getInt("lastSeq", 0);
            int seq = o.optInt("last", 0);
            if (seq < last) {                // the gateway restarted
                sp.edit().putInt("lastSeq", 0).apply();
                return;
            }
            JSONArray arr = o.optJSONArray("messages");
            if (arr == null || arr.length() == 0) return;
            sp.edit().putInt("lastSeq", seq).apply();
            Log.i(TAG, "poll seq=" + seq + " last=" + last);

            if (last == 0) return;           // first run only sets the baseline

            String text = null;
            for (int k = arr.length() - 1; k >= 0; k--) {
                JSONObject m = arr.getJSONObject(k);
                if ("assistant".equals(m.optString("role"))) {
                    text = m.optString("text");
                    break;
                }
            }
            if (text == null) return;
            if (MainActivity.visible && isScreenOn(ctx)) return;
            notify(ctx, text);
        } catch (Throwable t) {
            Log.i(TAG, "consume FAIL " + t);
        }
    }

    private static boolean isScreenOn(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    /** /sdcard/vipa-notify.<ext> if present, else the system default. */
    private static android.net.Uri notificationSound() {
        for (String n : new String[]{"vipa-notify.wav", "vipa-notify.mp3",
                                     "vipa-notify.m4a", "vipa-notify.ogg"}) {
            java.io.File f = new java.io.File("/sdcard/" + n);
            if (f.exists() && f.length() > 0) return android.net.Uri.fromFile(f);
        }
        return null;
    }

    private static void notify(Context ctx, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, 0);

            String one = text.replace("\n", " ");
            if (one.length() > 70) one = one.substring(0, 70) + "…";

            Notification.Builder nb = new Notification.Builder(ctx)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("vipa")
                    .setContentText(one)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis());
            android.net.Uri snd = notificationSound();
            if (snd != null) nb.setSound(snd);
            nm.notify(3000, nb.build());

            android.os.Vibrator v =
                    (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(400);
            Log.i(TAG, "notified: " + one);
        } catch (Throwable t) {
            Log.i(TAG, "notify FAIL " + t);
        }
    }

    static String read(InputStream is) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) o.write(buf, 0, n);
        is.close();
        return new String(o.toByteArray(), "UTF-8");
    }
}
