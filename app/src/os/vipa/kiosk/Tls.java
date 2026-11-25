package os.vipa.kiosk;

import java.net.HttpURLConnection;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * この端末（Android 5.1）は 2015 年時点の CA しか持っていない。
 * ngrok などが使う Let's Encrypt のルートは入っていないので、検証に失敗する。
 *
 * 失敗したときだけ、その接続に限って検証を緩める（ホスト名の確認は残す）。
 */
final class Tls {

    private static SSLSocketFactory relaxed;

    static synchronized SSLSocketFactory relaxed() {
        if (relaxed != null) return relaxed;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new java.security.SecureRandom());
            relaxed = ctx.getSocketFactory();
        } catch (Throwable t) {
            return null;
        }
        return relaxed;
    }

    static void relax(HttpURLConnection c) {
        if (!(c instanceof HttpsURLConnection)) return;
        SSLSocketFactory f = relaxed();
        if (f != null) ((HttpsURLConnection) c).setSSLSocketFactory(f);
    }
}
