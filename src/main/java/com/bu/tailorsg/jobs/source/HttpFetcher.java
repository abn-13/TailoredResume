package com.bu.tailorsg.jobs.source;

import com.bu.tailorsg.jobs.JobsProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Plain GET requests with a browser-like User-Agent and the configured timeout (spec: 10 s). */
@Component
public class HttpFetcher {

    public record Response(int status, String body, String contentType, URI finalUri) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final JobsProperties props;
    private final HttpClient client;

    public HttpFetcher(JobsProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(props.timeout())
                .build();
    }

    /** @throws IOException on network failure (callers treat it as "this source could not fetch it") */
    public Response get(URI uri, String accept) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(props.timeout())
                .header("User-Agent", props.userAgent())
                .header("Accept", accept)
                .header("Accept-Language", "en-SG,en;q=0.9")
                .GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Content-Type").orElse(""), response.uri());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
