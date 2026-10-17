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
 * Lever (spec 3, adapter 2): jobs.lever.co/{site}/{id} (or jobs.eu.lever.co), via the public Postings API
 * GET api.lever.co/v0/postings/{site}/{id} (github.com/lever/postings-api). The posting has no company name,
 * so the site name stands in for it (correct it in the JobSpec editor).
 */
@Component
@Order(3)
public class LeverSource implements JobSource {

    static final Pattern PATH = Pattern.compile("^/([^/]+)/([0-9a-fA-F-]{36})");

    private final HttpFetcher fetcher;
    private final JobsProperties props;
    private final JsonMapper json;

    public LeverSource(HttpFetcher fetcher, JobsProperties props, JsonMapper json) {
        this.fetcher = fetcher;
        this.props = props;
        this.json = json;
    }

    public String name() {
        return "Lever";
    }

    public boolean supports(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        return (host.equals("jobs.lever.co") || host.equals("jobs.eu.lever.co"))
                && uri.getPath() != null && PATH.matcher(uri.getPath()).find();
    }

    public Optional<FetchedJob> fetch(URI uri) {
        Matcher m = PATH.matcher(uri.getPath());
        if (!m.find()) {
            return Optional.empty();
        }
        String base = uri.getHost().equals("jobs.eu.lever.co") ? props.leverEuApiBase() : props.leverApiBase();
        try {
            HttpFetcher.Response response = fetcher.get(URI.create(base + "/v0/postings/" + m.group(1) + "/" + m.group(2)),
                    "application/json");
            return response.ok() ? Optional.of(map(json.readTree(response.body()), m.group(1))) : Optional.empty();
        } catch (IOException | JacksonException e) {
            return Optional.empty();
        }
    }

    FetchedJob map(JsonNode posting, String site) {
        StringBuilder text = new StringBuilder();
        JsonNode categories = posting.path("categories");
        String location = categories.path("location").stringValue("");
        if (!location.isBlank()) {
            text.append("Location: ").append(location).append('\n');
        }
        if (!categories.path("commitment").stringValue("").isBlank()) {
            text.append("Commitment: ").append(categories.path("commitment").stringValue("")).append('\n');
        }
        if (!categories.path("team").stringValue("").isBlank()) {
            text.append("Team: ").append(categories.path("team").stringValue("")).append('\n');
        }
        text.append('\n').append(posting.path("descriptionPlain").stringValue("").strip()).append("\n\n");
        for (JsonNode list : posting.path("lists").values()) {
            text.append(list.path("text").stringValue("")).append('\n')
                    .append(HtmlText.fromHtml(list.path("content").stringValue(""))).append("\n\n");
        }
        text.append(posting.path("additionalPlain").stringValue("").strip());
        String company = site.isEmpty() ? "" : Character.toUpperCase(site.charAt(0)) + site.substring(1);
        return new FetchedJob(name(), posting.path("text").stringValue(""), company, location, text.toString().strip(),
                List.of(), null, null, null);
    }
}
