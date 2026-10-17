package com.bu.tailorsg.llm;

import com.bu.tailorsg.llm.LlmPing.PingResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Makes one real call to the extractor model (Haiku 5.5, well under a cent).
 *
 * <p>Skipped automatically when ANTHROPIC_API_KEY is not set. To skip it even with a key:
 * {@code ./mvnw test -DexcludedGroups=live}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
class ClaudePingLiveTest {

    @Autowired
    private LlmPing llmPing;

    @Autowired
    private LlmUsageMeter meter;

    @Test
    void extractorModelAnswersWithSchemaValidJsonAndEchoesTheNonce() {
        PingResult result = llmPing.ping();

        assertThat(result.error()).as("ping error").isNull();
        assertThat(result.ok()).isTrue();
        assertThat(result.model()).isEqualTo("claude-haiku-5-5");
        assertThat(result.message()).isNotBlank();
        assertThat(result.usage().input()).isPositive();
        assertThat(meter.snapshot().calls()).isPositive();
    }
}
