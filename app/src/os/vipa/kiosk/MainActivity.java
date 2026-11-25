package os.vipa.kiosk;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

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

    public static String SERVER = "http://192.168.2.101:8099";
    private String home = SERVER + "/";
    private static final String TAG = "VIPA";
    private static final int MAX_RETRY = 6;

    private WebView web;
    private int retry = 0;
    private final Handler handler = new Handler();
    private final String[] skLabels = {"Reply", "", "\u2193", ""};

    public static volatile boolean visible = false;

    private MediaRecorderHolder rec = new MediaRecorderHolder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        SERVER = resolveServer();
        home = assetHome();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);

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
            public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler handler,
                                           android.net.http.SslError err) {
                String host = null;
                try {
                    host = new URL(SERVER).getHost();
                } catch (Throwable ignored) {}
                String failed = err == null ? null : err.getUrl();
                // This phone only has 2015 CAs, so allow our own host only.
                if (host != null && failed != null && failed.contains(host)) {
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

        setContentView(web);
        hidePointerIcon();
        web.loadUrl(home);
        handler.postDelayed(new Runnable() {
            public void run() { applySoftkeys(); }
        }, 700);
        schedulePoll();
    }

    static final long POLL_MS = 5000L;

    static void scheduleNext(ContextLike c, long delayMs) {
        // unused
    }

    private void schedulePoll() {
        try {
            android.app.AlarmManager am =
                    (android.app.AlarmManager) getSystemService(ALARM_SERVICE);
            android.content.Intent i = new android.content.Intent(this, PollReceiver.class);
            android.app.PendingIntent pi =
                    android.app.PendingIntent.getBroadcast(this, 0, i, 0);
            am.setExact(android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + POLL_MS, pi);
            Log.i(TAG, "next poll in " + POLL_MS + "ms");
        } catch (Throwable t) {
            Log.i(TAG, "alarm FAIL " + t);
        }
    }

    interface ContextLike {}

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
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

    /** Use /sdcard/vipa.url when present, else the built-in default. */
    private String resolveServer() {
        try {
            java.io.File f = new java.io.File("/sdcard/vipa.url");
            if (f.exists()) {
                java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.FileReader(f));
                String line = r.readLine();
                r.close();
                if (line != null && line.trim().length() > 0) {
                    String u = line.trim();
                    if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    Log.i(TAG, "server from file: " + u);
                    return u;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "url file n/a " + t);
        }
        return SERVER;
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
                web.loadUrl("javascript:sk(-" + dip(280) + ")"); return true;
            }
            if (c == KeyEvent.KEYCODE_DPAD_RIGHT) {
                web.loadUrl("javascript:sk(" + dip(280) + ")"); return true;
            }
            if (c == KeyEvent.KEYCODE_F1) {          // top-left soft key
                toggleVoice(); return true;
            }
            if (c == KeyEvent.KEYCODE_F2 || c == KeyEvent.KEYCODE_F3
                    || c == KeyEvent.KEYCODE_F4
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

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) applySoftkeys();
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
            setSk(0, "Reply");
            web.loadUrl("javascript:hideRecording()");
        }
    }

    private void stopVoice() {
        setSk(0, "Reply");
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
