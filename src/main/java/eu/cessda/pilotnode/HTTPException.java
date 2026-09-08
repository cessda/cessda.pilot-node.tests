package eu.cessda.pilotnode;

import java.io.IOException;
import java.io.Serial;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Exception for HTTP responses that return a failure status code (i.e. 400+). The status code and
 * the request URI are stored for logging purposes.
 */
public class HTTPException extends IOException {
    @Serial
    private static final long serialVersionUID = 2075217549327361589L;

    private final HttpRequest httpRequest;
    private final HttpResponse<?> httpReponse;


    /**
     * Constructs a {@link HTTPException} with the specified status code and request URI.
     *
     * @param httpRequest the request.
     * @param httpReponse the response.
     */
    public HTTPException(HttpRequest httpRequest, HttpResponse<?> httpReponse) {
        super(httpRequest.uri() + ": Server returned " + httpReponse.statusCode());
        this.httpRequest = httpRequest;
        this.httpReponse = httpReponse;
    }

    public HttpRequest getRequest() {
        return httpRequest;
    }

    public HttpResponse<?> getResponse() {
        return httpReponse;
    }
}
