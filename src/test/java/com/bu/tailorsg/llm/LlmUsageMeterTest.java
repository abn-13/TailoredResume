package com.bu.tailorsg.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class LlmUsageMeterTest {

    private final LlmUsageMeter meter = new LlmUsageMeter(TestFixtures.props());

    @Test
    void sonnetCostUsesPerMillionTokenPrices() {
        // 10k in * $2 + 2k out * $10 + 1k cache write * $2.50 + 5k cache read * $0.10 (per million)
        TokenUsage usage = new TokenUsage(10_000, 2_000, 1_000, 5_000);

        assertThat(meter.estimateCost("claude-sonnet-5-5", usage))
                .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("0.043"));
    }

    @Test
    void haikuPromptAtThresholdUsesBaseRates() {
        TokenUsage usage = new TokenUsage(100_000, 1_000, 0, 0); // exactly 100k: not "over"

        assertThat(meter.estimateCost("claude-haiku-5-5", usage))
                .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("0.0105"));
    }

    @Test
    void haikuPromptOverThresholdUsesLongContextRatesIncludingCachedTokens() {
        // 90k uncached + 20k cache read = 110k prompt tokens, so the whole request is billed at the higher rates
        TokenUsage usage = new TokenUsage(90_000, 1_000, 0, 20_000);

        // 90k * $0.50 + 1k * $2.50 + 20k * $0.05 (per million) = 0.045 + 0.0025 + 0.001
        assertThat(meter.estimateCost("claude-haiku-5-5", usage))
                .hasValueSatisfying(cost -> assertThat(cost).isEqualByComparingTo("0.0485"));
    }

    @Test
    void unknownModelIsReportedAsUnpricedNotFree() {
        assertThat(meter.estimateCost("claude-unknown-9", new TokenUsage(1000, 1000, 0, 0))).isEmpty();

        meter.record("ping", 1, "claude-unknown-9", new TokenUsage(1000, 1000, 0, 0));

        assertThat(meter.snapshot().anyUnpriced()).isTrue();
        assertThat(meter.snapshot().calls()).isEqualTo(1);
    }

    @Test
    void sessionTotalAccumulatesAndFormatsForTheFooter() {
        meter.record("ping", 1, "claude-sonnet-5-5", new TokenUsage(1_000, 500, 0, 0)); // $0.007
        meter.record("ping", 1, "claude-sonnet-5-5", new TokenUsage(1_000, 500, 0, 0)); // $0.007

        LlmUsageMeter.Snapshot snapshot = meter.snapshot();
        assertThat(snapshot.calls()).isEqualTo(2);
        assertThat(snapshot.totalCostUsd()).isEqualByComparingTo(new BigDecimal("0.014"));
        assertThat(snapshot.costDisplay()).isEqualTo("$0.0140");
    }
}
