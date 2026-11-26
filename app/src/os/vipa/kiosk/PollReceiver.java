package os.vipa.kiosk;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
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
 * Poll for new replies and notify when the phone is not being looked at.
 * When the app is in front and the screen is on, the page updates itself.
 */
public class PollReceiver extends BroadcastReceiver {

    private static final String TAG = "VIPA";

    @Override
    public void onReceive(final Context c, Intent i) {
        final Context ctx = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                android.os.PowerManager.WakeLock wl = null;
                try {
                    android.os.PowerManager pm = (android.os.PowerManager)
                            ctx.getSystemService(Context.POWER_SERVICE);
                    wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                            "vipa:poll");
                    wl.acquire(30000);
                } catch (Throwable ignored) {
                }
                try {
                    poll(ctx);
                } catch (Throwable t) {
                    Log.i(TAG, "poll FAIL " + t);
                } finally {
                    if (wl != null) {
                        try { wl.release(); } catch (Throwable ignored) {}
                    }
                    try {
                        android.app.AlarmManager am = (android.app.AlarmManager)
                                ctx.getSystemService(Context.ALARM_SERVICE);
                        android.content.Intent ni =
                                new android.content.Intent(ctx, PollReceiver.class);
                        android.app.PendingIntent np = android.app.PendingIntent
                                .getBroadcast(ctx, 0, ni, 0);
                        am.setExact(android.app.AlarmManager.RTC_WAKEUP,
                                System.currentTimeMillis() + MainActivity.pollIntervalMs(), np);
                    } catch (Throwable t) {
                        Log.i(TAG, "reschedule FAIL " + t);
                    }
                }
            }
        }).start();
    }

    private void poll(Context ctx) throws Exception {
        // The screen is open: the page polls on its own, so do not ask twice.
        if (MainActivity.visible) return;
        SharedPreferences sp = ctx.getSharedPreferences("vipa", 0);
        int last = sp.getInt("lastSeq", 0);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection)
                    new URL(MainActivity.SERVER + "/api/out?since=" + last).openConnection();
            Tls.relax(conn);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200) return;
            JSONObject o = new JSONObject(read(conn.getInputStream()));
            int seq = o.optInt("last", 0);
            if (seq < last) {                       // the gateway restarted
                sp.edit().putInt("lastSeq", 0).apply();
                return;
            }
            JSONArray arr = o.optJSONArray("messages");
            if (arr == null || arr.length() == 0) return;
            sp.edit().putInt("lastSeq", seq).apply();
            Log.i(TAG, "poll seq=" + seq + " last=" + last);

            if (last == 0) return;                  // first run only sets the baseline

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
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean isScreenOn(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    private void notify(Context ctx, String text) {
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
            nm.notify(3000, nb.build());

            android.os.Vibrator v =
                    (android.os.Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(400);
            Log.i(TAG, "notified: " + one);
        } catch (Throwable t) {
            Log.i(TAG, "notify FAIL " + t);
        }
    }

    private static String read(InputStream is) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) o.write(buf, 0, n);
        is.close();
        return new String(o.toByteArray(), "UTF-8");
    }
}
