package com.bu.tailorsg.web;

import com.bu.tailorsg.llm.LlmPing;
import com.bu.tailorsg.llm.LlmPing.PingResult;
import com.bu.tailorsg.llm.LlmPing.Status;
import com.bu.tailorsg.llm.TokenUsage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Boots the whole app (in-memory H2, "test" profile) and exercises it through MockMvc:
 * no real server or port, but real controllers, filters and Thymeleaf templates, so template
 * mistakes fail here rather than in the browser.
 *
 * <p>{@link LlmPing} is replaced by a Mockito mock so no test here can spend money,
 * even if ANTHROPIC_API_KEY happens to be set.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebLayerTest {

    @Autowired
    private MockMvcTester mvc; // AssertJ-style MockMvc (Spring 6.2+)

    @MockitoBean
    private LlmPing llmPing;

    @Test
    void healthReportsDatabaseUpAndModelIdsButNeverTheKey() {
        assertThat(mvc.get().uri("/api/health"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo("UP");
                    assertThat(json).extractingPath("$.database").isEqualTo("UP");
                    assertThat(json).extractingPath("$.llm.writerModel").isEqualTo("claude-sonnet-5-5");
                    assertThat(json).extractingPath("$.llm.extractorModel").isEqualTo("claude-haiku-5-5");
                    assertThat(json).extractingPath("$.llm.apiKeyConfigured").isNotNull();
                });
        assertThat(mvc.get().uri("/api/health")).bodyText().doesNotContain("sk-ant");
    }

    @Test
    void pagesRenderWithLayoutNavAndFooter() {
        for (String uri : new String[] {"/", "/master", "/master/view", "/master/import", "/updates", "/add-info",
                "/applications/new", "/settings"}) {
            assertThat(mvc.get().uri(uri))
                    .as(uri)
                    .hasStatusOk()
                    .bodyText()
                    .contains("TailorSG", "Master Resume", "LLM spend this session");
        }
        assertThat(mvc.get().uri("/applications/new")).bodyText().contains("Step 1 of 3", "Screenshot(s)", "Paste");
    }

    /**
     * Regression: a fragment named like an HTML element ("nav") also matched every {@code <nav>} in
     * layout.html, so the Master tabs leaked into every page's header. Each layout piece must appear once.
     */
    @Test
    void layoutFragmentsAppearExactlyOncePerPage() throws Exception {
        for (String uri : new String[] {"/", "/master", "/master/history", "/master/view", "/master/import", "/updates",
                "/add-info", "/settings"}) {
            String html = mvc.get().uri(uri).exchange().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(countOf(html, "class=\"topbar\"")).as(uri + " top bar").isEqualTo(1);
            assertThat(countOf(html, "class=\"site-footer\"")).as(uri + " footer").isEqualTo(1);
            assertThat(countOf(html, "aria-label=\"Master resume\"")).as(uri + " master tabs")
                    .isEqualTo(uri.startsWith("/master") || uri.startsWith("/updates") ? 1 : 0);
        }
    }

    private static int countOf(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void htmxIsServedLocallyFromTheWebJar() {
        assertThat(mvc.get().uri("/webjars/htmx.org/dist/htmx.min.js")).hasStatusOk();
    }

    @Test
    void settingsPingReturnsResultFragmentAndOutOfBandCostUpdate() {
        when(llmPing.ping()).thenReturn(new PingResult(Status.OK, "claude-haiku-5-5", "Hello there",
                1, new TokenUsage(60, 40, 0, 0), "$0.0000", 812, null));

        assertThat(mvc.post().uri("/settings/ping").header("HX-Request", "true"))
                .hasStatusOk()
                .bodyText()
                .contains("Connected.", "claude-haiku-5-5", "Hello there", "812")
                .contains("id=\"llm-cost\"", "hx-swap-oob=\"true\"")
                .doesNotContain("<html"); // a fragment, not a whole page
    }

    @Test
    void settingsPingShowsFailureMessage() {
        when(llmPing.ping()).thenReturn(new PingResult(Status.NOT_CONFIGURED, null, null, 0,
                TokenUsage.ZERO, null, 0, "ANTHROPIC_API_KEY is not set."));

        assertThat(mvc.post().uri("/settings/ping"))
                .hasStatusOk()
                .bodyText()
                .contains("Failed.", "ANTHROPIC_API_KEY is not set.");
    }

    @Test
    void apiPingMapsNotConfiguredTo503() {
        when(llmPing.ping()).thenReturn(new PingResult(Status.NOT_CONFIGURED, null, null, 0,
                TokenUsage.ZERO, null, 0, "ANTHROPIC_API_KEY is not set."));

        assertThat(mvc.post().uri("/api/llm/ping"))
                .hasStatus(503)
                .bodyJson().extractingPath("$.status").isEqualTo("NOT_CONFIGURED");
    }

    // ---- LocalOnlyRequestFilter ----

    @Test
    void rejectsForeignHostHeader() {
        assertThat(mvc.get().uri("/api/health").with(request -> {
            request.setServerName("attacker.example"); // what a DNS-rebinding page would send
            return request;
        })).hasStatus(403);
    }

    @Test
    void rejectsCrossSiteWrites() {
        when(llmPing.ping()).thenReturn(PingResultStub.ok());

        assertThat(mvc.post().uri("/settings/ping").header("Origin", "https://attacker.example")).hasStatus(403);
        assertThat(mvc.post().uri("/settings/ping").header("Sec-Fetch-Site", "cross-site")).hasStatus(403);
        assertThat(mvc.post().uri("/settings/ping").header("Origin", "null")).hasStatus(403);
    }

    @Test
    void allowsSameOriginBrowserWritesAndCurl() {
        when(llmPing.ping()).thenReturn(PingResultStub.ok());

        assertThat(mvc.post().uri("/settings/ping")
                .header("Origin", "http://localhost:8080")
                .header("Sec-Fetch-Site", "same-origin")).hasStatusOk();
        assertThat(mvc.post().uri("/settings/ping").header("Origin", "http://127.0.0.1:8080")).hasStatusOk();
        assertThat(mvc.post().uri("/settings/ping")).hasStatusOk(); // curl: no Origin, no Sec-Fetch-Site
    }

    private static final class PingResultStub {
        static PingResult ok() {
            return new PingResult(Status.OK, "claude-haiku-5-5", "Hi", 1, TokenUsage.ZERO, "$0.0000", 1, null);
        }
    }
}
