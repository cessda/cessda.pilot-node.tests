package eu.cessda.pilotnode;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@Component
public class HttpUtils {
    private static final int MAX_RETRIES = 3;
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofMillis(1000);

    private final HttpClient client;

    /**
     * Builds an {@link HttpClient}.
     */
    public HttpUtils() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Parse the Retry-After header of the HTTP response.
     *
     * @param headers the headers of the HTTP response.
     * @return the delay in milliseconds, or -1 if the header was missing or invalid.
     */
    private static Duration parseRetryAfterHeader(HttpHeaders headers) {
        var retryAfterHeader = headers.firstValue("Retry-After");

        if (retryAfterHeader.isPresent()) {
            try {
                // Try parsing as an integer, sleep for the time specified
                var delaySeconds = Long.parseLong(retryAfterHeader.get());
                return Duration.ofSeconds(delaySeconds);
            } catch (NumberFormatException e) {
                // catch parsing failure if header is a HTTP date
            }

            try {
                var httpDate = ZonedDateTime.parse(retryAfterHeader.get(), DateTimeFormatter.RFC_1123_DATE_TIME);
                return Duration.between(ZonedDateTime.now(), httpDate);
            } catch (DateTimeParseException e) {
                // catch parsing failure if header an invalid format
            }
        }

        // header not present or invalid
        return null;
    }

    /**
     * Perform the HTTP request, retrying in case of connection errors.
     *
     * @param request     the request to perform.
     * @param bodyHandler the body handler for the response.
     * @return the response.
     * @throws IOException          if an IO error occurred when sending the response and the maximum retries have been exceeded.
     * @throws InterruptedException if the operation is interrupted.
     */
    @SuppressWarnings({"java:S3776", "java:S135"}) // Any other way of implementing this logic is more complicated
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
        // Retry counter
        int retries = 0;

        // Stores any previous errors thrown during previous attempts.
        IOException previousException = null;

        // Container for IO errors thrown during the current attempt.
        IOException currentException;

        while (true) {
            try {
                var response = client.send(request, bodyHandler);

                int responseCode = response.statusCode();

                if (responseCode < 400) {
                    // If successful, return the response
                    return response;
                } else if (responseCode == 429 || responseCode == 503) {
                    // Try to parse the Retry-After header, fall back to default behaviour if the header is not present
                    var retryAfterDelay = parseRetryAfterHeader(response.headers());
                    if (retryAfterDelay != null) {
                        Thread.sleep(retryAfterDelay.toMillis());

                        // Reset the loop without incrementing the amount of retries
                        continue;
                    }
                }

                // Request failed, set error status
                currentException = new HTTPException(request, response);
                if (responseCode != 429 && responseCode < 500) {
                    // 400 response codes, apart from 429, are caused by client errors; break and throw the error
                    break;
                }
            } catch (IOException e) {
                // Store the IO error (i.e. connection timeouts, failed DNS lookups, etc.)
                currentException = e;
            }

            /*
             * Error handling
             */
            if (previousException != null) {
                // Suppress the previous exception
                currentException.addSuppressed(previousException);
            }

            if (retries >= MAX_RETRIES) {
                // Maximum amount of retries reached; give up and throw the error
                break;
            }

            // Store the current error for the next loop and increment the amount of retires
            previousException = currentException;
            retries++;

            Thread.sleep(DEFAULT_RETRY_DELAY.toMillis());
        }

        // Request failed
        throw currentException;
    }
}
