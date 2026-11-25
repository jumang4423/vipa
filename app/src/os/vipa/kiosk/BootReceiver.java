package os.vipa.kiosk;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Start the app on boot and re-arm the polling alarm. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            Intent open = new Intent(c, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(open);
        } catch (Throwable ignored) {
        }
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            Intent pi = new Intent(c, PollReceiver.class);
            PendingIntent p = PendingIntent.getBroadcast(c, 0, pi, 0);
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 60 * 1000L, 5 * 60 * 1000L, p);
        } catch (Throwable ignored) {
        }
    }
}
