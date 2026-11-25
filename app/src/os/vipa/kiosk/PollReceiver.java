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
 * 5分ごとに新着を見て、画面を見ていなければ通知する。
 * 画面が点いていてアプリが前面なら、SSE が画面を更新するので通知しない。
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
                                System.currentTimeMillis() + MainActivity.POLL_MS, np);
                    } catch (Throwable t) {
                        Log.i(TAG, "reschedule FAIL " + t);
                    }
                }
            }
        }).start();
    }

    private void poll(Context ctx) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MainActivity.SERVER + "/api/messages?limit=1")
                    .openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            if (code != 200) return;
            String body = read(conn.getInputStream());
            JSONObject o = new JSONObject(body);
            JSONArray arr = o.getJSONArray("messages");
            if (arr.length() == 0) return;
            JSONObject m = arr.getJSONObject(0);
            int seq = m.getInt("seq");
            String role = m.getString("role");
            String text = m.getString("text");

            SharedPreferences sp = ctx.getSharedPreferences("vipa", 0);
            int last = sp.getInt("lastSeq", 0);
            Log.i(TAG, "poll seq=" + seq + " last=" + last + " role=" + role);
            if (seq <= last) return;
            sp.edit().putInt("lastSeq", seq).apply();

            if (last == 0) return;                 // 初回は基準を作るだけ
            if (!"assistant".equals(role)) return; // 自分の発話では通知しない
            Log.i(TAG, "new: visible=" + MainActivity.visible
                    + " screen=" + isScreenOn(ctx));
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
                    .setContentTitle("vipaOS")
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
