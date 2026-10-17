package com.bu.tailorsg.llm;

import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.config.TailorSgProperties.LongContext;
import com.bu.tailorsg.config.TailorSgProperties.ModelPrice;
import com.bu.tailorsg.config.TailorSgProperties.RoleSettings;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/** Builds the LLM collaborators by hand so unit tests run without starting Spring. */
final class TestFixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private TestFixtures() {}

    /** Same shape and prices as application.yml, so cost assertions match real behaviour. */
    static TailorSgProperties props() {
        return new TailorSgProperties(
                new TailorSgProperties.Models("claude-sonnet-5-5", "claude-haiku-5-5"),
                new TailorSgProperties.Llm(Duration.ofSeconds(30), 0,
                        new RoleSettings("high", 16000, true), new RoleSettings("medium", 8000, false)),
                Map.of(
                        "claude-sonnet-5-5", new ModelPrice(bd("2.00"), bd("10.00"), bd("2.50"), bd("0.10"), null),
                        "claude-haiku-5-5", new ModelPrice(bd("0.10"), bd("0.50"), bd("0.125"), bd("0.01"),
                                new LongContext(100_000, bd("0.50"), bd("2.50"), bd("0.625"), bd("0.05")))),
                new TailorSgProperties.Storage("./data/exports"));
    }

    static JsonSchemaService schemas() {
        return new JsonSchemaService(new DefaultResourceLoader(), JSON);
    }

    static PromptLibrary prompts() {
        return new PromptLibrary(new DefaultResourceLoader());
    }

    static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
