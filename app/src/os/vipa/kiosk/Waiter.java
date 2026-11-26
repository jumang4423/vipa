package os.vipa.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

/**
 * One held request instead of a poll.
 *
 * The phone asks /api/wait once and the server keeps the request open until there is
 * something to say. Between answers nothing is sent, so the radio sleeps; when a message
 * does appear, the packet that carries it wakes the CPU (Android 5.1 has no Doze) and this
 * thread notifies the user. That is the whole trick: no push service, and no polling.
 */
final class Waiter {

    private static final String TAG = "VIPA";
    private static final int HOLD = 240;             // seconds the server may hold the request
    private static final long SAFE_ALARM = 15 * 60 * 1000L;
    private static final long STALE = 20 * 60 * 1000L;

    private static Thread thread;
    private static volatile long lastOk = 0L;

    private Waiter() {
    }

    /** True while the held request is healthy; the alarm fallback then stays silent. */
    static boolean fresh() {
        return System.currentTimeMillis() - lastOk < STALE;
    }

    static void ensure(Context c) {
        synchronized (Waiter.class) {
            if (thread != null && thread.isAlive()) return;
            final Context app = c.getApplicationContext();
            thread = new Thread(new Runnable() {
                public void run() {
                    loop(app);
                }
            }, "vipa-wait");
            thread.setDaemon(true);
            thread.start();
            Log.i(TAG, "waiter started");
        }
    }

    private static void loop(Context app) {
        long backoff = 0;
        while (true) {
            // While the screen is on, the page holds its own request; do not double the traffic.
            if (MainActivity.visible) {
                sleep(3000);
                continue;
            }
            try {
                SharedPreferences sp = app.getSharedPreferences("vipa", 0);
                int last = sp.getInt("lastSeq", 0);
                long t0 = System.currentTimeMillis();
                Log.i(TAG, "hold since=" + last);
                JSONObject o = Box.get("/api/wait?since=" + last + "&timeout=" + HOLD,
                        12000, (HOLD + 30) * 1000);
                Log.i(TAG, "hold done " + (System.currentTimeMillis() - t0) + "ms");
                lastOk = System.currentTimeMillis();
                backoff = 0;
                Box.consume(app, o);
            } catch (Throwable t) {
                backoff = backoff == 0 ? 5000 : Math.min(backoff * 2, 300000);
                Log.i(TAG, "wait FAIL " + t + " retry in " + backoff + "ms");
                sleep(backoff);
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
