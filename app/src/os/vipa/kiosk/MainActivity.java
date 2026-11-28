package os.vipa.kiosk;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * vipa terminal.
 *
 * Shows the chat the home machine serves, and uploads voice to it.
 * top-left = reply (record) / bottom keys = jump to end / d-pad = scroll
 */
public class MainActivity extends Activity {

    // The tunnel (/sdcard/vipa.url, the ngrok URL) is the only normal entrance: it works at
    // home and outside, so a SIM changes nothing. The local address is a *fallback* for when
    // ngrok is down, and the app returns to the tunnel as soon as it answers again.
    private static final String DEFAULT_URL = "http://192.168.2.101:8099";
    public static volatile String REMOTE = DEFAULT_URL;
    public static volatile String SERVER = "http://192.168.2.101:8099";
    public static volatile boolean onLan = false;
    private static MainActivity inst = null;
    private static android.content.Context sCtx = null;
    private static android.content.Context appCtx = null;
    private String home = SERVER + "/";
    private static final String TAG = "VIPA";
    private static final int MAX_RETRY = 6;

    private WebView web;
    private FrameLayout root;
    private LinearLayout textOverlay;
    private EditText textInput;
    private long lastF3 = 0L;
    private boolean pageReady = false;
    private int retry = 0;
    private final Handler handler = new Handler();
    private final String[] skLabels = {"Voice", "Switch", "Text", "Clear"};

    public static volatile boolean visible = false;

    private MediaRecorderHolder rec = new MediaRecorderHolder();
    private android.net.wifi.WifiManager.WifiLock wifiLock = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        inst = this;
        appCtx = getApplicationContext();
        sCtx = appCtx;
        REMOTE = resolveServer();
        SERVER = REMOTE;
        home = assetHome();
        if (!REMOTE.equals(DEFAULT_URL)) {
            saveUrl(REMOTE);
        } else {
            retryUrl(1);      // /sdcard is not always mounted when the kiosk starts
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        web.addJavascriptInterface(new Bridge(), "vipa");

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setDefaultTextEncodingName("utf-8");
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setDomStorageEnabled(true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.i(TAG, "JS " + m.message());
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return true;
                return !(url.startsWith(SERVER) || url.startsWith("file://"));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                v.loadUrl("javascript:try{setHold(" + pageHoldSeconds() + ")}catch(e){}");
            }

            @Override
            public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError err) {
                String host = null, remoteHost = null;
                try {
                    host = new URL(SERVER).getHost();
                    remoteHost = new URL(REMOTE).getHost();
                } catch (Throwable ignored) {}
                String failed = err == null ? null : err.getUrl();
                // This phone only has 2015 CAs, so allow our own hosts only (both the local
                // address and the tunnel, because the page may still be on the old one).
                if (failed != null && ((host != null && failed.contains(host))
                        || (remoteHost != null && failed.contains(remoteHost)))) {
                    Log.i(TAG, "ssl: proceed " + failed);
                    handler.proceed();
                } else {
                    Log.i(TAG, "ssl: cancel " + failed);
                    handler.cancel();
                }
            }

            @Override
            public void onReceivedError(WebView v, int code, String desc, String failingUrl) {
                if (retry < MAX_RETRY) {
                    retry++;
                    handler.postDelayed(new Runnable() {
                        public void run() { web.loadUrl(home); }
                    }, 3000L * retry);
                } else {
                    v.loadData(offlinePage(desc), "text/html; charset=utf-8", "utf-8");
                }
            }
        });

        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildTextOverlay();
        setContentView(root);
        hidePointerIcon();
        web.loadUrl(home);
        handler.postDelayed(new Runnable() {
            public void run() { applySoftkeys(); }
        }, 700);
        refreshCharging();
        watchPower();
        holdWifi();
        watchNetwork();
        Waiter.ensure(this);
        schedulePoll();
    }

    // Messages normally arrive on the waiter's held request (see Waiter.java). This alarm is
    // only a safety net, so it can be slow: it exists to restart the waiter if it ever dies,
    // not to fetch. The page holds its own request while the screen is on.
    static volatile boolean charging = false;

    static long pollIntervalMs() {
        return charging ? 60L * 1000L : 5L * 60L * 1000L;
    }

    /** Refresh, and say whether the answer actually changed (battery broadcasts are frequent). */
    private boolean chargingChanged() {
        boolean before = charging;
        refreshCharging();
        return before != charging;
    }

    private void refreshCharging() {
        try {
            android.content.Intent i = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            int status = i == null ? -1
                    : i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            charging = false;
        }
        Log.i(TAG, "charging=" + charging + " poll=" + pollIntervalMs() + "ms");
    }

    /** A WifiLock keeps the radio associated while the screen is off; without it the station
     *  sleeps, DNS stops resolving and every retry fails until something wakes the phone. */
    private void holdWifi() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL, "vipa:wait");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            Log.i(TAG, "wifi lock held");
        } catch (Throwable t) {
            Log.i(TAG, "wifi lock FAIL " + t);
        }
    }

    /** When the network comes or goes, stop waiting for the old socket and ask again. */
    private void watchNetwork() {
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    Log.i(TAG, "network changed: " + i.getExtras());
                    Waiter.wake();
                    if (web != null) handler.post(new Runnable() {
                        public void run() { web.loadUrl("javascript:try{pollForce()}catch(e){}"); }
                    });
                }
            };
            registerReceiver(r,
                    new android.content.IntentFilter(android.net.ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Throwable t) {
            Log.i(TAG, "watchNetwork FAIL " + t);
        }
    }

    private void watchPower() {
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    // ACTION_BATTERY_CHANGED arrives every few seconds; only a real change of
                    // the charger may interrupt the request the page is holding.
                    if (!chargingChanged()) return;
                    Waiter.wake();
                    if (web == null) return;
                    handler.post(new Runnable() {
                        public void run() {
                            web.loadUrl("javascript:try{setHold(" + pageHoldSeconds()
                                    + ");pollForce()}catch(e){}");
                        }
                    });
                }
            };
            android.content.IntentFilter f =
                    new android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED);
            f.addAction(android.content.Intent.ACTION_POWER_DISCONNECTED);
            // some handsets do not broadcast the power actions; every battery change is a hint
            f.addAction(android.content.Intent.ACTION_BATTERY_CHANGED);
            registerReceiver(r, f);
        } catch (Throwable t) {
            Log.i(TAG, "watchPower FAIL " + t);
        }
    }

    /** Arm the safety-net alarm. Works from the activity and from PollReceiver alike. */
    static void scheduleAlarm(android.content.Context ctx, long delayMs) {
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) ctx.getSystemService(android.content.Context.ALARM_SERVICE);
            android.content.Intent i = new android.content.Intent(ctx, PollReceiver.class);
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getBroadcast(ctx, 0, i, 0);
            am.setExact(android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delayMs, pi);
            Log.i(TAG, "next poll in " + delayMs + "ms");
        } catch (Throwable t) {
            Log.i(TAG, "alarm FAIL " + t);
        }
    }

    private void schedulePoll() {
        scheduleAlarm(this, pollIntervalMs());
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        try {
            if (web != null) {
                web.resumeTimers();
                web.onResume();
            }
        } catch (Throwable ignored) {}
        refreshCharging();
        String u = readUrlFile();
        if (u != null && !u.equals(SERVER)) {
            SERVER = u;
            REMOTE = u;
            saveUrl(u);
            Log.i(TAG, "server changed: " + u);
            home = assetHome();
            if (web != null && pageReady) web.loadUrl(home);
        }
        chooseBaseAsync();
        if (web != null && pageReady) handler.post(new Runnable() {
            public void run() {
                web.loadUrl("javascript:try{setHold(" + pageHoldSeconds() + ");pollForce()}catch(e){}");
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) { applySoftkeys(); refreshCharging(); }
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        visible = false;
        // The WebView keeps its timers running while the screen is off, so the page would keep
        // polling and mark messages as "seen" that nobody saw — and the notification never fires.
        try {
            if (web != null) {
                web.pauseTimers();
                web.onPause();
            }
        } catch (Throwable ignored) {}
        super.onPause();
    }

    /** Charging on Wi-Fi is free of both battery and data, so it checks every 5 s.
     *  Everything else stays at one check a minute. */
    static boolean fastMode() {
        if (!charging || sCtx == null) return false;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    sCtx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected()
                    && ni.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        } catch (Throwable t) {
            return false;
        }
    }

    /** How long one request may be held: ngrok cuts a request at 5 minutes. */
    static int holdSeconds() {
        return fastMode() ? 5 : 60;
    }

    static int pageHoldSeconds() {
        return fastMode() ? 5 : 60;
    }

    static boolean isAwake() {
        try {
            if (!visible || sCtx == null) return false;
            android.os.PowerManager pm = (android.os.PowerManager)
                    sCtx.getSystemService(android.content.Context.POWER_SERVICE);
            return pm.isScreenOn();
        } catch (Throwable t) {
            return visible;
        }
    }

    /** The page reports what it actually drew, so a message read on screen is never
     *  announced again as a notification when the phone is closed. */
    public class Bridge {
        @android.webkit.JavascriptInterface
        public void seen(int seq) {
            if (seq < 0) return;
            // Only what was drawn on a screen someone was looking at counts as seen.
            if (!isAwake()) return;
            try {
                android.content.SharedPreferences sp = getSharedPreferences("vipa", 0);
                // 0 is a real state: the box was emptied, so nothing has been seen yet.
                android.content.SharedPreferences.Editor e = sp.edit().putBoolean("init", true);
                if (seq != sp.getInt("lastSeq", 0)) e.putInt("lastSeq", seq);
                e.apply();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ which entrance

    /** Tunnel first; the local address only while the tunnel is down. */
    static void chooseBaseAsync() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    chooseBase();
                } catch (Throwable t) {
                    Log.i(TAG, "chooseBase FAIL " + t);
                }
            }
        }, "vipa-base").start();
    }

    static synchronized void chooseBase() {
        if (appCtx == null) return;

        JSONObject h = health(REMOTE, 8000);
        if (h != null) {
            setBase(REMOTE, false);
            JSONArray arr = h.optJSONArray("lan");
            if (arr != null && arr.length() > 0) {
                appCtx.getSharedPreferences("vipa", 0).edit()
                        .putString("lan", arr.optString(0, "")).apply();
            }
            return;
        }

        String lan = appCtx.getSharedPreferences("vipa", 0).getString("lan", "");
        if (lan != null && lan.length() > 0 && health(lan, 1500) != null) {
            setBase(lan, true);
            return;
        }
        setBase(REMOTE, false);
    }

    private static JSONObject health(String base, int readMs) {
        try {
            JSONObject o = Box.getAt(base, "/api/health", readMs, readMs);
            return o != null && o.optBoolean("ok", false) ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setBase(String url, boolean lan) {
        if (url.equals(SERVER) && lan == onLan) return;
        SERVER = url;
        onLan = lan;
        Log.i(TAG, "base -> " + url + (lan ? " (lan fallback)" : " (tunnel)"));
        final MainActivity a = inst;
        if (a == null) return;
        a.handler.post(new Runnable() {
            public void run() {
                if (a.web == null) return;
                a.home = a.assetHome();
                a.web.loadUrl("javascript:try{setApi(" + jsonString(SERVER) + ");setHold("
                        + pageHoldSeconds() + ")}catch(e){}");
            }
        });
    }

    private static String jsonString(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------ softkeys

    private void applySoftkeys() {
        try {
            Class<?> c = Class.forName("jp.kyocera.kcfp.util.KCfpSoftkeyGuide");
            Object g = c.getMethod("getSoftkeyGuide", Window.class).invoke(null, getWindow());
            java.lang.reflect.Method setText =
                    c.getMethod("setText", int.class, CharSequence.class);
            java.lang.reflect.Method setEnabled =
                    c.getMethod("setEnabled", int.class, boolean.class);
            java.lang.reflect.Method setPrio =
                    c.getMethod("setPriority", int.class, int.class);
            int prio = c.getField("SK_PRIORITY_DIALOG").getInt(null);
            int[] idx = {
                    c.getField("INDEX_SK1").getInt(null),
                    c.getField("INDEX_SK2").getInt(null),
                    c.getField("INDEX_SK3").getInt(null),
                    c.getField("INDEX_SK4").getInt(null),
            };
            for (int i = 0; i < idx.length; i++) {
                setText.invoke(g, idx[i], skLabels[i]);
                setPrio.invoke(g, idx[i], prio);
                setEnabled.invoke(g, idx[i], skLabels[i].length() > 0);
            }
            c.getMethod("show").invoke(g);
            try { c.getMethod("pendingApply", boolean.class).invoke(g, false); } catch (Throwable ignored) {}
            try { c.getMethod("invalidate").invoke(g); } catch (Throwable ignored) {}
            c.getMethod("translucent", boolean.class).invoke(g, true);
            Log.i(TAG, "softkeys ok " + skLabels[0] + "/" + skLabels[2]);
        } catch (Throwable t) {
            Log.i(TAG, "softkeys FAIL " + t);
        }
    }

    private void setSk(int i, String label) {
        skLabels[i] = label;
        applySoftkeys();
    }

    // ------------------------------------------------------------ pointer

    private void hidePointerIcon() {
        try {
            Class<?> pc = Class.forName("android.view.PointerIcon");
            Object none = pc.getMethod("getNullIcon").invoke(null);
            if (none != null) Log.i(TAG, "pointer null icon available");
        } catch (Throwable t) {
            Log.i(TAG, "pointer n/a " + t);
        }
    }

    private float hoverY = -1f;
    private int pendingDy = 0;
    private boolean flushQueued = false;

    private void queueScroll(int dy) {
        pendingDy += dy;
        if (!flushQueued) {
            flushQueued = true;
            handler.postDelayed(new Runnable() {
                public void run() {
                    flushQueued = false;
                    int d = pendingDy;
                    pendingDy = 0;
                    if (d != 0) web.loadUrl("javascript:sk(" + d + ")");
                }
            }, 40);
        }
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == android.view.MotionEvent.ACTION_HOVER_ENTER) {
            hoverY = ev.getY();
            return true;
        }
        if (a == android.view.MotionEvent.ACTION_HOVER_MOVE) {
            if (hoverY < 0f) { hoverY = ev.getY(); return true; }
            float dy = ev.getY() - hoverY;
            hoverY = ev.getY();
            int step = (int) dy;
            if (step != 0) queueScroll(step);
            return true;
        }
        if (a == android.view.MotionEvent.ACTION_HOVER_EXIT) {
            hoverY = -1f;
            return true;
        }
        return super.dispatchGenericMotionEvent(ev);
    }

    // ------------------------------------------------------------ keys

    /** The UI lives in the APK; only the server is passed in the query. */
    private String assetHome() {
        try {
            return "file:///android_asset/index.html?u="
                    + java.net.URLEncoder.encode(SERVER, "UTF-8");
        } catch (Throwable t) {
            return "file:///android_asset/index.html";
        }
    }

    /** Use /sdcard/vipa.url when present, else the last one we read, else the built-in. */
    private String resolveServer() {
        String u = readUrlFile();
        if (u != null) {
            Log.i(TAG, "server from file: " + u);
            return u;
        }
        String saved = getSharedPreferences("vipa", 0).getString("url", "");
        if (saved != null && saved.length() > 0) {
            Log.i(TAG, "server from memory: " + saved);
            return saved;
        }
        return DEFAULT_URL;
    }

    private static String readUrlFile() {
        try {
            java.io.File f = new java.io.File("/sdcard/vipa.url");
            if (!f.exists()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String line = r.readLine();
            r.close();
            if (line == null || line.trim().length() == 0) return null;
            String u = line.trim();
            if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            return u;
        } catch (Throwable t) {
            Log.i(TAG, "url file n/a " + t);
            return null;
        }
    }

    private void saveUrl(String url) {
        saveUrl(sCtx, url);
    }

    private static void saveUrl(android.content.Context ctx, String url) {
        try {
            if (ctx == null) return;
            ctx.getSharedPreferences("vipa", 0).edit().putString("url", url).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Resolve the server address without an Activity (the alarm may start the process). */
    static void initServer(android.content.Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
        String u = readUrlFile();
        if (u == null && sCtx != null) {
            String saved = sCtx.getSharedPreferences("vipa", 0).getString("url", "");
            if (saved != null && saved.length() > 0) u = saved;
        }
        if (u == null || u.length() == 0) return;      // nothing better than the default yet
        if (u.equals(SERVER)) return;
        SERVER = u;
        REMOTE = u;
        Log.i(TAG, "server (init): " + u);
        saveUrl(sCtx, u);
    }

    /** Wait for the SD card, then use the address it holds. */
    private void retryUrl(final int attempt) {
        if (attempt > 6) return;
        handler.postDelayed(new Runnable() {
            public void run() {
                String u = readUrlFile();
                if (u == null) {
                    retryUrl(attempt + 1);
                    return;
                }
                saveUrl(u);
                if (u.equals(SERVER)) return;
                SERVER = u;
                REMOTE = u;
                Log.i(TAG, "server from file (late): " + u);
                home = assetHome();
                if (web != null && pageReady) web.loadUrl(home);
            }
        }, 5000L * attempt);
    }

    private int dip(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int c = e.getKeyCode();
            Log.i(TAG, "KEY " + c + " " + KeyEvent.keyCodeToString(c));
            if (c == KeyEvent.KEYCODE_DPAD_UP) {
                web.loadUrl("javascript:sk(-" + dip(72) + ")"); return true;
            }
            if (c == KeyEvent.KEYCODE_DPAD_DOWN) {
                web.loadUrl("javascript:sk(" + dip(72) + ")"); return true;
            }
            if (c == KeyEvent.KEYCODE_DPAD_CENTER) {
                web.loadUrl("javascript:nextPage()"); return true;
            }
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) {
                web.loadUrl("javascript:navLR(-1)"); return true;
            }
            if (c == KeyEvent.KEYCODE_DPAD_RIGHT) {
                web.loadUrl("javascript:navLR(1)"); return true;
            }
            if (c == KeyEvent.KEYCODE_F2) {          // top-right: switch view
                web.loadUrl("javascript:toggleView()"); return true;
            }
            if (c == KeyEvent.KEYCODE_F1) {          // top-left soft key
                web.loadUrl("javascript:goThread()");
                toggleVoice(); return true;
            }
            if (c == KeyEvent.KEYCODE_F3) {          // bottom-left: the platform text field
                lastF3 = System.currentTimeMillis();
                if (textOverlay.getVisibility() == View.VISIBLE) {
                    hideTextEntry();
                } else {
                    web.loadUrl("javascript:goThread()");
                    showTextEntry();
                }
                return true;
            }
            if (c == KeyEvent.KEYCODE_F4) {
                // A3 (Text) sends F3 immediately followed by F4. A lone F4 is the
                // bottom-right (Clear) key, which this handset does not document.
                if (System.currentTimeMillis() - lastF3 < 400L) return true;
                confirmClear(); return true;
            }
            if (c >= KeyEvent.KEYCODE_F5 && c <= KeyEvent.KEYCODE_F12) {   // bottom-right: clear
                confirmClear();
                return true;
            }
            if (c == KeyEvent.KEYCODE_F2
                    || c == KeyEvent.KEYCODE_BACK) {          // lower keys: jump to the end
                web.loadUrl("javascript:bottom()"); return true;
            }
            Log.i(TAG, "DKEY " + c + " " + KeyEvent.keyCodeToString(c));
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            retry = 0;
            web.loadUrl(home);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    // ------------------------------------------------------------ voice

    private void toggleVoice() {
        if (rec.recording) stopVoice(); else startVoice();
    }

    private void startVoice() {
        try {
            File f = recFile();
            if (f.exists()) f.delete();
            rec.start(f);
            setSk(0, "■ Stop");
            web.loadUrl("javascript:showRecording()");
            Log.i(TAG, "rec start");
        } catch (Throwable t) {
            Log.i(TAG, "rec FAIL " + t);
            rec.release();
            setSk(0, "Voice");
            web.loadUrl("javascript:hideRecording()");
        }
    }

    private void stopVoice() {
        setSk(0, "Voice");
        web.loadUrl("javascript:hideRecording()");
        web.loadUrl("javascript:showTyping()");
        new Thread(new Runnable() {
            public void run() {
                try {
                    rec.stop();
                } catch (Throwable t) {
                    Log.i(TAG, "rec stop FAIL " + t);
                    return;
                }
                File f = recFile();
                Log.i(TAG, "rec size=" + f.length());
                if (f.length() < 2000) return;
                upload(f);
            }
        }).start();
    }

    private void upload(File f) {
        HttpURLConnection c = null;
        boolean ok = false;
        String why = "";
        try {
            byte[] audio = readBytes(f);
            c = (HttpURLConnection) new URL(SERVER + "/api/voice").openConnection();
            Tls.relax(c);
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "audio/mp4");
            c.setFixedLengthStreamingMode(audio.length);
            OutputStream os = c.getOutputStream();
            os.write(audio);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            Log.i(TAG, "voice http=" + code + " " + body);
            ok = code < 400;
            if (code == 503) why = "Voice is not set up (no GROQ_API_KEY)";
            else if (!ok) why = "Cannot send voice: " + body;
        } catch (Throwable t) {
            Log.i(TAG, "voice FAIL " + t);
        } finally {
            if (c != null) c.disconnect();
            if (!ok) {
                final String msg = why;
                handler.post(new Runnable() {
                    public void run() {
                        web.loadUrl("javascript:showError(" + quote(msg) + ")");
                    }
                });
            }
        }
    }

    private static String quote(String s) {
        try {
            return org.json.JSONObject.quote(s == null ? "" : s);
        } catch (Throwable t) {
            return "\"\"";
        }
    }

    // ------------------------------------------------------------ clear (new session)

    private void confirmClear() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Clear")
                .setMessage("Clear this session and start a new one?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        clearSession();
                    }
                })
                .show();
    }

    private void clearSession() {
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    for (String path : new String[]{"/api/rotate", "/api/reset"}) {
                        c = (HttpURLConnection) new URL(SERVER + path).openConnection();
                        Tls.relax(c);
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(8000);
                        c.setRequestMethod("POST");
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "application/json");
                        byte[] body = "{}".getBytes("UTF-8");
                        c.setFixedLengthStreamingMode(body.length);
                        OutputStream os = c.getOutputStream();
                        os.write(body); os.flush(); os.close();
                        c.getResponseCode();
                        c.disconnect();
                        c = null;
                    }
                    Log.i(TAG, "session cleared");
                } catch (Throwable t) {
                    Log.i(TAG, "clear FAIL " + t);
                } finally {
                    if (c != null) c.disconnect();
                }
                handler.post(new Runnable() {
                    public void run() { web.loadUrl(home); }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ text field (platform IME)

    private void buildTextOverlay() {
        textOverlay = new LinearLayout(this);
        textOverlay.setOrientation(LinearLayout.VERTICAL);
        textOverlay.setBackgroundColor(0xFF000000);
        textOverlay.setPadding(dip(12), dip(12), dip(12), dip(12));
        textOverlay.setVisibility(View.GONE);

        textInput = new EditText(this);
        textInput.setInputType(InputType.TYPE_CLASS_TEXT);
        textInput.setImeOptions(EditorInfo.IME_ACTION_SEND
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        textInput.setTextColor(0xFFFFFFFF);
        textInput.setHintTextColor(0xFF6F6F6F);
        textInput.setHint("");
        textInput.setTextSize(18);
        textInput.setBackgroundColor(0xFF101010);
        textInput.setPadding(dip(10), dip(10), dip(10), dip(10));
        textInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    sendTextEntry();
                    return true;
                }
                return false;
            }
        });
        textOverlay.addView(textInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(textOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final Runnable closeTextAfterIdle = new Runnable() {
        public void run() {
            if (textOverlay != null && textOverlay.getVisibility() == View.VISIBLE) {
                Log.i(TAG, "text entry closed after idle");
                hideTextEntry();
            }
        }
    };

    private void armIdleClose() {
        handler.removeCallbacks(closeTextAfterIdle);
        handler.postDelayed(closeTextAfterIdle, 10 * 60 * 1000L);   // 10 min
    }

    private void showTextEntry() {
        textInput.setText("");
        textOverlay.setVisibility(View.VISIBLE);
        textInput.requestFocus();
        armIdleClose();
        textInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            public void onTextChanged(CharSequence c, int a, int b, int d) { armIdleClose(); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(textInput, InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable t) {
            Log.i(TAG, "ime n/a " + t);
        }
    }

    @Override
    public void onBackPressed() {
        if (textOverlay != null && textOverlay.getVisibility() == View.VISIBLE) {
            hideTextEntry();
            return;
        }
        super.onBackPressed();
    }

    private void hideTextEntry() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);
        } catch (Throwable ignored) {}
        textInput.setText("");
        textOverlay.setVisibility(View.GONE);
        web.requestFocus();
    }

    private void sendTextEntry() {
        final String text = textInput.getText().toString().trim();
        if (text.length() == 0) { hideTextEntry(); return; }
        hideTextEntry();
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(SERVER + "/api/in").openConnection();
                    Tls.relax(c);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    byte[] body = ("{\"text\":" + org.json.JSONObject.quote(text) + "}").getBytes("UTF-8");
                    c.setFixedLengthStreamingMode(body.length);
                    OutputStream os = c.getOutputStream();
                    os.write(body); os.flush(); os.close();
                    Log.i(TAG, "text sent http=" + c.getResponseCode());
                } catch (Throwable t) {
                    Log.i(TAG, "text send FAIL " + t);
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private File recFile() {
        return new File(getCacheDir(), "rec.m4a");
    }

    // ------------------------------------------------------------ util

    private static byte[] readBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }

    private static String read(InputStream is) {
        if (is == null) return "(no body)";
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) o.write(buf, 0, n);
            is.close();
            return new String(o.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "(read err " + t + ")";
        }
    }

    private String offlinePage(String reason) {
        String safe = reason == null ? "" : reason.replace("<", "(").replace(">", ")");
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;background:#000;color:#fff;"
                + "font-family:sans-serif}div{padding:16px;font-size:19px;line-height:1.7}"
                + ".s{color:#777;font-size:15px;margin-top:18px}</style></head><body><div>"
                + "Cannot reach the server.<br>Press the menu key to retry."
                + "<div class='s'>" + safe + "</div>"
                + "</div></body></html>";
    }

    /** Thin MediaRecorder wrapper (null-safe). */
    static class MediaRecorderHolder {
        android.media.MediaRecorder mr;
        volatile boolean recording;

        void start(File out) throws Exception {
            mr = new android.media.MediaRecorder();
            mr.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            mr.setAudioSamplingRate(16000);
            mr.setAudioEncodingBitRate(32000);
            mr.setAudioChannels(1);
            mr.setOutputFile(out.getAbsolutePath());
            mr.prepare();
            mr.start();
            recording = true;
        }

        void stop() {
            recording = false;
            if (mr != null) {
                try { mr.stop(); } catch (Throwable ignored) {}
                try { mr.release(); } catch (Throwable ignored) {}
                mr = null;
            }
        }

        void release() {
            recording = false;
            if (mr != null) {
                try { mr.release(); } catch (Throwable ignored) {}
                mr = null;
            }
        }
    }
}
