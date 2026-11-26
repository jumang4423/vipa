package os.vipa.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

/**
 * The safety net, not the main channel.
 *
 * The waiter holds one request open, which is what normally delivers messages. This alarm
 * only makes sure that thread is alive: if it is healthy this does no network at all, and if
 * it died (the process was killed) it fetches once and restarts the waiter.
 */
public class PollReceiver extends BroadcastReceiver {

    private static final String TAG = "VIPA";

    @Override
    public void onReceive(final Context c, Intent i) {
        final Context ctx = c.getApplicationContext();
        Waiter.ensure(ctx);

        if (MainActivity.visible || Waiter.fresh()) {
            MainActivity.scheduleAlarm(ctx, MainActivity.pollIntervalMs());
            return;
        }

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
                    SharedPreferences sp = ctx.getSharedPreferences("vipa", 0);
                    int last = sp.getInt("lastSeq", 0);
                    JSONObject o = Box.get("/api/out?since=" + last, 4000, 5000);
                    Box.consume(ctx, o);
                } catch (Throwable t) {
                    Log.i(TAG, "poll FAIL " + t);
                } finally {
                    if (wl != null) {
                        try { wl.release(); } catch (Throwable ignored) {}
                    }
                    MainActivity.scheduleAlarm(ctx, MainActivity.pollIntervalMs());
                }
            }
        }).start();
    }
}
