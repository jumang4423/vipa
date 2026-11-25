package os.vipa.kiosk;

import java.net.HttpURLConnection;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * This phone (Android 5.1) only ships CAs from 2015, so it cannot verify the
 * Let's Encrypt roots behind ngrok. Relax verification for a single connection
 * (hostname checks stay on).
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
