package eu.cessda.pilotnode;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;

@Component
public class HttpUtils {
    /**
     * Builds an {@link HttpClient} that accepts all TLS certificates.
     *
     * <p>This is intentional: pilot node catalogue endpoints may be
     * signed by CAs not present in the default JVM trust store (e.g.
     * GEANT/IGTF CAs common in research infrastructure). This tool is
     * a monitoring client, not a security-sensitive data processor, so
     * bypassing certificate validation is an acceptable trade-off.</p>
     */
    @Bean
    public static HttpClient trustAllHttpClient() {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(
                                X509Certificate[] chain, String authType) {
                        }

                        public void checkServerTrusted(
                                X509Certificate[] chain, String authType) {
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, null);
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(sslContext)
                    .build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            // Should never happen with a standard JVM
            throw new IllegalStateException(
                    "Failed to create trust-all SSLContext", e);
        }
    }
}
