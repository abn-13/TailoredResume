package com.bu.tailorsg.checks.truth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** TRUTH-02's number normalisation (spec 6E: "35 %"->"35%", "1,200"->"1200", "S$5k"->"5000", "2x"->"2"). */
class TruthNumbersTest {

    @Test
    void normalisesTheSpecExamples() {
        assertThat(TruthChecks.Truth02Numbers.numbers("cut by 35 % for 1,200 users, saving S$5k, 2x faster", Set.of()))
                .containsExactly("35", "1200", "5000", "2");
        assertThat(TruthChecks.Truth02Numbers.numbers("3.50 hours and S$2m", Set.of())).containsExactly("3.5", "2000000");
    }

    @Test
    void ignoresDigitsInsideWordsAndVersionedSkillNames() {
        assertThat(TruthChecks.Truth02Numbers.numbers("p95 latency on Java 21 and OAuth 2.0 with 3 services",
                Set.of("java 21", "oauth 2.0"))).containsExactly("3");
        assertThat(TruthChecks.Truth02Numbers.numbers("B2B and S3 buckets", Set.of())).isEmpty();
    }
}
