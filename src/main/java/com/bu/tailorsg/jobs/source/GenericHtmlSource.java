package com.bu.tailorsg.jobs.source;

import com.bu.tailorsg.jobs.JobsProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Any other page (spec 3, adapters 3 and 4): GET with a normal browser User-Agent and a 10 s timeout, then
 * use the schema.org JobPosting in {@code <script type="application/ld+json">} if there is one, otherwise the
 * page's main text (readability-style: drop navigation and boilerplate, keep the densest content block).
 *
 * <p>Blocked (never bypassed): LinkedIn, HTTP 401/403/429, robots.txt disallow, login walls, and pages with
 * under 500 characters of text (usually built by JavaScript or behind a login).
 */
@Component
@Order(100)
public class GenericHtmlSource implements JobSource {

    private static final Set<Integer> BLOCKED_STATUS = Set.of(401, 403, 429);

    private final HttpFetcher fetcher;
    private final RobotsPolicy robots;
    private final JobsProperties props;
    private final JsonMapper json;

    public GenericHtmlSource(HttpFetcher fetcher, RobotsPolicy robots, JobsProperties props, JsonMapper json) {
        this.fetcher = fetcher;
        this.robots = robots;
        this.props = props;
        this.json = json;
    }

    public String name() {
        return "Web page";
    }

    public boolean supports(URI uri) {
        return true;
    }

    public Optional<FetchedJob> fetch(URI uri) {
        String host = uri.getHost().toLowerCase();
        if (host.equals("linkedin.com") || host.endsWith(".linkedin.com")) {
            throw new BlockedSiteException("LinkedIn requires a login");
        }
        if (!robots.allows(uri)) {
            throw new BlockedSiteException("the site's robots.txt asks automated tools not to fetch this page");
        }
        HttpFetcher.Response response;
        try {
            response = fetcher.get(uri, "text/html,application/xhtml+xml");
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach " + host + ": " + e.getMessage());
        }
        if (BLOCKED_STATUS.contains(response.status())) {
            throw new BlockedSiteException("the site answered HTTP " + response.status());
        }
        if (!response.ok()) {
            throw new IllegalStateException("The page answered HTTP " + response.status() + ".");
        }
        Document doc = Jsoup.parse(response.body(), response.finalUri().toString());
        Optional<FetchedJob> fromJsonLd = jsonLd(doc);
        if (fromJsonLd.isPresent() && fromJsonLd.get().text().length() >= 200) {
            return fromJsonLd;
        }
        boolean loginForm = !doc.select("input[type=password]").isEmpty();
        String text = mainText(doc);
        if (text.length() < props.minTextChars() || (loginForm && text.length() < 2000)) {
            throw new BlockedSiteException(loginForm ? "the page asks you to log in"
                    : "the page has almost no readable text (it may need a login or JavaScript)");
        }
        return Optional.of(FetchedJob.textOnly(name(), doc.title(), null, text));
    }

    /** schema.org JobPosting, wherever it sits: a single object, an array, or an @graph. */
    Optional<FetchedJob> jsonLd(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                for (JsonNode node : candidates(json.readTree(script.data()))) {
                    if (isJobPosting(node)) {
                        String description = HtmlText.fromHtml(node.path("description").stringValue(""));
                        String company = node.path("hiringOrganization").path("name").stringValue("");
                        JsonNode address = node.path("jobLocation").isArray() ? node.path("jobLocation").path(0) : node.path("jobLocation");
                        String location = address.path("address").path("addressLocality").stringValue("");
                        return Optional.of(new FetchedJob("Web page (JSON-LD)", node.path("title").stringValue(""), company,
                                location, description, List.of(), null, null, null));
                    }
                }
            } catch (JacksonException e) {
                // malformed JSON-LD is common; try the next script or fall back to page text
            }
        }
        return Optional.empty();
    }

    private static List<JsonNode> candidates(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.values().forEach(nodes::add);
        } else {
            nodes.add(root);
            root.path("@graph").values().forEach(nodes::add);
        }
        return nodes;
    }

    private static boolean isJobPosting(JsonNode node) {
        JsonNode type = node.path("@type");
        if (type.isArray()) {
            return type.values().stream().anyMatch(t -> "JobPosting".equals(t.stringValue("")));
        }
        return "JobPosting".equals(type.stringValue(""));
    }

    /** Readability-style: remove chrome, then keep the element whose paragraphs and list items hold the most text. */
    static String mainText(Document doc) {
        Document copy = doc.clone();
        copy.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg, button").remove();
        Element best = copy.body();
        int bestScore = 0;
        for (Element candidate : copy.select("main, article, [role=main], section, div")) {
            int score = 0;
            for (Element p : candidate.select("> p, > ul > li, > ol > li, > div > p, > section > p, > h2, > h3")) {
                score += p.text().length();
            }
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null ? "" : HtmlText.fromElement(best);
    }
}
