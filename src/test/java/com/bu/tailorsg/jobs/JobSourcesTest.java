package com.bu.tailorsg.jobs;

import com.bu.tailorsg.jobs.source.BlockedSiteException;
import com.bu.tailorsg.jobs.source.FetchedJob;
import com.bu.tailorsg.jobs.source.GenericHtmlSource;
import com.bu.tailorsg.jobs.source.GreenhouseSource;
import com.bu.tailorsg.jobs.source.HtmlText;
import com.bu.tailorsg.jobs.source.HttpFetcher;
import com.bu.tailorsg.jobs.source.JobSourceChain;
import com.bu.tailorsg.jobs.source.LeverSource;
import com.bu.tailorsg.jobs.source.MyCareersFutureSource;
import com.bu.tailorsg.jobs.source.RobotsPolicy;
import com.bu.tailorsg.jobs.source.UrlGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The adapter chain against a local server. The three API responses are real, saved from the live APIs on
 * 2026-10-17 (fixtures/jobs/*.json): MyCareersFuture's undocumented v2 API, Greenhouse's Job Board API and
 * Lever's Postings API. The HTML pages are synthetic.
 */
class JobSourcesTest {

    private static final String MCF_UUID = "d79c61fae9edc00c420cec7caf2c7087";
    private static final String LEVER_ID = "6ed76ce8-4156-4b60-b120-403538bd66cd";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private JobSourceChain chain;
    private String base;

    static String fixture(String name) {
        try {
            return new DefaultResourceLoader().getResource("classpath:fixtures/jobs/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String LONG_TEXT = "We are hiring a Backend Engineer to build payment APIs in Java and Spring Boot. ".repeat(12);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, Object[]> routes = Map.of(
                "/v2/jobs/" + MCF_UUID, new Object[] {200, fixture("mcf-job.json")},
                "/v1/boards/stripe/jobs/8172487", new Object[] {200, fixture("gh-job.json")},
                "/v0/postings/palantir/" + LEVER_ID, new Object[] {200, fixture("lever-job.json")},
                "/jsonld.html", new Object[] {200, "<html><head><title>Careers</title><script type=\"application/ld+json\">"
                        + "{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"WebPage\"},{\"@type\":\"JobPosting\","
                        + "\"title\":\"Data Engineer\",\"hiringOrganization\":{\"name\":\"Acme Pte Ltd\"},"
                        + "\"jobLocation\":{\"address\":{\"addressLocality\":\"Singapore\"}},\"description\":\"<p>" + LONG_TEXT + "</p>\"}]}"
                        + "</script></head><body>App shell</body></html>"},
                "/article.html", new Object[] {200, "<html><body><nav>Home Jobs About</nav><main><h1>Backend Engineer</h1><p>"
                        + LONG_TEXT + "</p><ul><li>Java</li><li>Spring Boot</li></ul></main><footer>(c) Acme</footer></body></html>"},
                "/login.html", new Object[] {200, "<html><body><form><input type=\"text\" name=\"u\"><input type=\"password\" name=\"p\">"
                        + "</form><p>Sign in to view this job</p></body></html>"},
                "/forbidden.html", new Object[] {403, "Forbidden"},
                "/robots.txt", new Object[] {200, "User-agent: *\nDisallow: /private/\nAllow: /private/ok.html\n"},
                "/private/job.html", new Object[] {200, "<html><body><p>" + LONG_TEXT + "</p></body></html>"});
        server.createContext("/", exchange -> {
            Object[] route = routes.get(exchange.getRequestURI().getPath());
            int status = route == null ? 404 : (int) route[0];
            byte[] body = (route == null ? "not found" : (String) route[1]).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        JobsProperties props = new JobsProperties(Duration.ofSeconds(5), "TestAgent", 500, base, base, base, base, true);
        HttpFetcher fetcher = new HttpFetcher(props);
        chain = new JobSourceChain(List.of(new MyCareersFutureSource(fetcher, props, JSON), new GreenhouseSource(fetcher, props, JSON),
                new LeverSource(fetcher, props, JSON), new GenericHtmlSource(fetcher, new RobotsPolicy(fetcher), props, JSON)));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void myCareersFutureUsesTheApiAndItsStructuredFields() {
        FetchedJob job = chain.fetch(URI.create("https://www.mycareersfuture.gov.sg/job/engineering/senior-software-engineer-codex-solutions-" + MCF_UUID));

        assertThat(job.source()).isEqualTo("MyCareersFuture");
        assertThat(job.title()).isEqualTo("Senior Software Engineer");
        assertThat(job.company()).isEqualTo("CODEX SOLUTIONS PTE. LTD.");
        assertThat(job.salaryMin()).isEqualTo(6000.0);
        assertThat(job.salaryMax()).isEqualTo(7500.0);
        assertThat(job.skills()).contains("Kubernetes");
        assertThat(job.text()).contains("Position Overview", "Minimum years of experience: 4", "Skills listed on MyCareersFuture")
                .doesNotContain("<p>", "<strong");
    }

    @Test
    void greenhouseDecodesItsEntityEncodedHtml() {
        FetchedJob job = chain.fetch(URI.create("https://boards.greenhouse.io/stripe/jobs/8172487"));

        assertThat(job.source()).isEqualTo("Greenhouse");
        assertThat(job.title()).isEqualTo("Abuse Investigator");
        assertThat(job.company()).isEqualTo("Stripe");
        assertThat(job.text()).contains("About Stripe").doesNotContain("&lt;", "<h2>", "&amp;");
        assertThat(chain.fetch(URI.create("https://job-boards.greenhouse.io/stripe/jobs/8172487")).source()).isEqualTo("Greenhouse");
    }

    @Test
    void leverCombinesDescriptionListsAndClosing() {
        FetchedJob job = chain.fetch(URI.create("https://jobs.lever.co/palantir/" + LEVER_ID));

        assertThat(job.source()).isEqualTo("Lever");
        assertThat(job.title()).isEqualTo("Administrative Business Partner");
        assertThat(job.location()).isEqualTo("Singapore, Singapore");
        assertThat(job.text()).contains("What We Require", "Commitment: Full-time");
    }

    @Test
    void genericPagesPreferJsonLdThenMainText() {
        FetchedJob ld = chain.fetch(URI.create(base + "/jsonld.html"));
        assertThat(ld.source()).isEqualTo("Web page (JSON-LD)");
        assertThat(ld.title()).isEqualTo("Data Engineer");
        assertThat(ld.company()).isEqualTo("Acme Pte Ltd");

        FetchedJob article = chain.fetch(URI.create(base + "/article.html"));
        assertThat(article.text()).contains("Backend Engineer", "• Java").doesNotContain("Home Jobs About", "(c) Acme");
    }

    @Test
    void blockedSitesAreNeverBypassed() {
        assertThatThrownBy(() -> chain.fetch(URI.create("https://www.linkedin.com/jobs/view/123")))
                .isInstanceOf(BlockedSiteException.class).hasMessageContaining("Paste the JD text or upload screenshots");
        assertThatThrownBy(() -> chain.fetch(URI.create(base + "/login.html")))
                .isInstanceOfSatisfying(BlockedSiteException.class, e -> assertThat(e.reason()).contains("log in"));
        assertThatThrownBy(() -> chain.fetch(URI.create(base + "/forbidden.html")))
                .isInstanceOfSatisfying(BlockedSiteException.class, e -> assertThat(e.reason()).contains("403"));
        assertThatThrownBy(() -> chain.fetch(URI.create(base + "/private/job.html")))
                .isInstanceOfSatisfying(BlockedSiteException.class, e -> assertThat(e.reason()).contains("robots.txt"));
    }

    @Test
    void apiFailureFallsThroughInsteadOfFailing() {
        JobsProperties props = new JobsProperties(Duration.ofSeconds(5), "TestAgent", 500, base, base, base, base, true);
        HttpFetcher fetcher = new HttpFetcher(props);
        var mcf = new MyCareersFutureSource(fetcher, props, JSON);
        assertThat(mcf.fetch(URI.create("https://www.mycareersfuture.gov.sg/job/x/" + "0".repeat(32)))).isEmpty();
    }

    @Test
    void urlGuardRejectsNonHttpAndPrivateAddresses() {
        UrlGuard strict = new UrlGuard(new JobsProperties(Duration.ofSeconds(5), "x", 500, "", "", "", "", false));

        assertThatThrownBy(() -> strict.check("ftp://example.com/job")).hasMessageContaining("http(s)");
        assertThatThrownBy(() -> strict.check("not a link")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.check("http://127.0.0.1:8080/api/master")).hasMessageContaining("private");
        assertThatThrownBy(() -> strict.check("http://192.168.1.10/job")).hasMessageContaining("private");
    }

    @Test
    void htmlTextKeepsStructure() {
        assertThat(HtmlText.fromHtml("<h2>About</h2><p>We build&nbsp;things.</p><ul><li>Java</li><li>Go</li></ul>"))
                .isEqualTo("About\n\nWe build things.\n\n• Java\n• Go");
    }
}
