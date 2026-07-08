package eu.cessda.pilotnode;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
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
    public static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
