package com.bu.tailorsg.jobs.source;

import com.bu.tailorsg.jobs.JobsProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Greenhouse job boards (spec 3, adapter 2): boards.greenhouse.io/{board}/jobs/{id} or
 * job-boards.greenhouse.io/{board}/jobs/{id}, via the public Job Board API
 * GET boards-api.greenhouse.io/v1/boards/{board}/jobs/{id} (docs.greenhouse.io/job-board.html).
 * The "content" field is HTML that arrives entity-encoded, so it is unescaped once, then turned into text.
 */
@Component
@Order(2)
public class GreenhouseSource implements JobSource {

    static final Pattern PATH = Pattern.compile("^/([^/]+)/jobs/(\\d+)");

    private final HttpFetcher fetcher;
    private final JobsProperties props;
    private final JsonMapper json;

    public GreenhouseSource(HttpFetcher fetcher, JobsProperties props, JsonMapper json) {
        this.fetcher = fetcher;
        this.props = props;
        this.json = json;
    }

    public String name() {
        return "Greenhouse";
    }

    public boolean supports(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        return (host.equals("boards.greenhouse.io") || host.endsWith("job-boards.greenhouse.io")
                || host.endsWith(".job-boards.greenhouse.io")) && uri.getPath() != null && PATH.matcher(uri.getPath()).find();
    }

    public Optional<FetchedJob> fetch(URI uri) {
        Matcher m = PATH.matcher(uri.getPath());
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            HttpFetcher.Response response = fetcher.get(URI.create(props.greenhouseApiBase() + "/v1/boards/"
                    + m.group(1) + "/jobs/" + m.group(2)), "application/json");
            return response.ok() ? Optional.of(map(json.readTree(response.body()))) : Optional.empty();
        } catch (IOException | JacksonException e) {
            return Optional.empty();
        }
    }

    FetchedJob map(JsonNode job) {
        String location = job.path("location").path("name").stringValue("");
        String text = HtmlText.fromEncodedHtml(job.path("content").stringValue(""));
        if (!location.isBlank()) {
            text = "Location: " + location + "\n\n" + text;
        }
        return new FetchedJob(name(), job.path("title").stringValue(""), job.path("company_name").stringValue(""),
                location, text, List.of(), null, null, null);
    }
}
