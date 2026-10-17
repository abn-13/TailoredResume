package com.bu.tailorsg.checks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 9: thresholds edited on the Settings page are validated, stored, used, and can be reset. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ThresholdServiceTest {

    @Autowired private ThresholdService thresholds;
    @Autowired private MockMvcTester mvc;

    @Test
    void validOverridesAreUsedAndCanBeReset() {
        String edited = thresholds.currentJson().replace("\"maxWords\": 28", "\"maxWords\": 30");
        assertThat(edited).contains("\"maxWords\": 30");
        assertThat(thresholds.save(edited)).isEmpty();
        assertThat(thresholds.current().lint().maxWords()).isEqualTo(30);
        assertThat(thresholds.isOverridden()).isTrue();

        thresholds.reset();
        assertThat(thresholds.current().lint().maxWords()).isEqualTo(28);
        assertThat(thresholds.isOverridden()).isFalse();
    }

    @Test
    void invalidOverridesAreRejectedWithReasons() {
        String swapped = thresholds.currentJson().replace("\"minWords\": 12", "\"minWords\": 40");
        assertThat(thresholds.save(swapped)).anySatisfy(p -> assertThat(p).contains("lint.maxWords must be greater than lint.minWords"));
        assertThat(thresholds.save("{ not json")).anySatisfy(p -> assertThat(p).startsWith("Not valid JSON"));
        assertThat(thresholds.save("{\"kw\": {\"requiredPass\": 2}}")).isNotEmpty();
        assertThat(thresholds.isOverridden()).isFalse();
    }

    @Test
    void settingsPageEditsThresholds() {
        assertThat(mvc.get().uri("/settings")).hasStatusOk().bodyText()
                .contains("Check thresholds", "application.yml defaults", "Recent model calls", "requiredPass");
        assertThat(mvc.post().uri("/settings/thresholds").param("thresholdsJson", "[]")).flash()
                .hasEntrySatisfying("flashError", e -> assertThat((String) e).startsWith("Not saved"));
        String edited = thresholds.currentJson().replace("\"maxIRun\": 3", "\"maxIRun\": 4");
        assertThat(mvc.post().uri("/settings/thresholds").param("thresholdsJson", edited)).flash().containsKey("flash");
        assertThat(thresholds.current().cl().maxIRun()).isEqualTo(4);
        assertThat(mvc.post().uri("/settings/thresholds/reset")).hasRedirectedUrl("/settings#thresholds");
        assertThat(thresholds.current().cl().maxIRun()).isEqualTo(3);
    }
}
