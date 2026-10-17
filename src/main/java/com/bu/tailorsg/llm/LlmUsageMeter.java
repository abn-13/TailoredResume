package com.bu.tailorsg.llm;

import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.config.TailorSgProperties.LongContext;
import com.bu.tailorsg.config.TailorSgProperties.ModelPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Logs token usage for every LLM call, keeps a running cost estimate for this app session, and (when a
 * repository is available) stores each call so the footer can also show the all-time total.
 *
 * <p>Costs are estimates from the per-MTok prices in application.yml, not billing data.
 */
@Component
public class LlmUsageMeter {

    private static final Logger log = LoggerFactory.getLogger(LlmUsageMeter.class);
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, ModelPrice> pricing;

    // Guarded by "this". Calls are rare (seconds apart), so a plain lock is simpler than atomics.
    private int calls;
    private BigDecimal totalCostUsd = BigDecimal.ZERO;
    private boolean anyUnpriced;

    /** Null in unit tests that build the meter by hand: then nothing is persisted. */
    private final LlmCallRepository callLog;
    private final java.time.Clock clock;

    public LlmUsageMeter(TailorSgProperties props) {
        this(props, null, java.time.Clock.systemUTC());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LlmUsageMeter(TailorSgProperties props, LlmCallRepository repository, java.time.Clock clock) {
        this.pricing = props.pricing();
        this.callLog = repository;
        this.clock = clock;
    }

    /** Records one call, logs it, and returns its estimated cost (zero when the model has no price entry). */
    public BigDecimal record(String promptId, int promptVersion, String model, TokenUsage usage) {
        Optional<BigDecimal> cost = estimateCost(model, usage);
        synchronized (this) {
            calls++;
            totalCostUsd = totalCostUsd.add(cost.orElse(BigDecimal.ZERO));
            anyUnpriced |= cost.isEmpty();
        }
        if (callLog != null) {
            callLog.save(new LlmCall(promptId, promptVersion, model, usage, cost.orElse(null), clock.instant()));
        }
        // Never log request/response bodies here: they contain the resume. Counts only.
        log.info("LLM call prompt={} v{} model={} tokens[in={} out={} cacheWrite={} cacheRead={}] cost={} sessionTotal={}",
                promptId, promptVersion, model, usage.input(), usage.output(), usage.cacheWrite(), usage.cacheRead(),
                cost.map(LlmUsageMeter::usd).orElse("unpriced"), usd(snapshot().totalCostUsd()));
        if (cost.isEmpty()) {
            log.warn("No price for model '{}' under tailorsg.pricing; cost estimate excludes this call", model);
        }
        return cost.orElse(BigDecimal.ZERO);
    }

    /** Estimated USD cost of one call, or empty when the model is not in tailorsg.pricing. */
    public Optional<BigDecimal> estimateCost(String model, TokenUsage usage) {
        ModelPrice price = pricing.get(model);
        if (price == null) {
            return Optional.empty();
        }
        // Haiku 5.5 bills the whole request at higher rates when the prompt exceeds a threshold.
        LongContext lc = price.longContext();
        boolean longPrompt = lc != null && usage.promptTokens() > lc.thresholdTokens();

        BigDecimal micros = BigDecimal.ZERO
                .add(per(usage.input(), longPrompt ? lc.input() : price.input()))
                .add(per(usage.output(), longPrompt ? lc.output() : price.output()))
                .add(per(usage.cacheWrite(), longPrompt ? lc.cacheWrite() : price.cacheWrite()))
                .add(per(usage.cacheRead(), longPrompt ? lc.cacheRead() : price.cacheRead()));
        return Optional.of(micros.divide(ONE_MILLION, 8, RoundingMode.HALF_UP));
    }

    public Snapshot snapshot() {
        long allCalls = callLog == null ? 0 : callLog.count();
        BigDecimal allCost = callLog == null ? BigDecimal.ZERO : callLog.totalCost();
        synchronized (this) {
            return new Snapshot(calls, totalCostUsd, anyUnpriced, Math.max(allCalls, calls), allCost.max(totalCostUsd));
        }
    }

    /** The last 20 calls, newest first (Settings page). */
    public java.util.List<LlmCall> recent() {
        return callLog == null ? java.util.List.of() : callLog.findTop20ByOrderByCreatedAtDescIdDesc();
    }

    private static BigDecimal per(long tokens, BigDecimal usdPerMillion) {
        return usdPerMillion.multiply(BigDecimal.valueOf(tokens));
    }

    static String usd(BigDecimal amount) {
        return "$" + amount.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Immutable view for the UI footer and health endpoint.
     *
     * @param allTimeCalls   calls stored in the database (this session included)
     * @param allTimeCostUsd their estimated cost
     */
    public record Snapshot(int calls, BigDecimal totalCostUsd, boolean anyUnpriced, long allTimeCalls, BigDecimal allTimeCostUsd) {

        /** e.g. "$0.0123", formatted here so templates stay free of number formatting. */
        public String costDisplay() {
            return usd(totalCostUsd);
        }

        public String allTimeDisplay() {
            return usd(allTimeCostUsd);
        }
    }
}
