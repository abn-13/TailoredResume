package com.bu.tailorsg.llm;

import com.bu.tailorsg.llm.LlmTransport.LlmReply;
import com.bu.tailorsg.llm.LlmTransport.LlmRequest;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends every prompt's schema to the real API as structured output, to prove the API can compile it.
 * The grammar size limit is unpublished, so {@link LlmSchemaLimitsTest}'s offline budget is an estimate;
 * this is the ground truth. A schema the API cannot compile comes back as HTTP 400, which the transport
 * throws as an {@link LlmException} carrying the API's message (e.g. "The compiled grammar is too large").
 *
 * <p>Cost: a rejected schema is free; a compiled one is about 1,000 input and 16 output tokens on Haiku
 * 5.5, so all prompts together cost about $0.001. Every prompt uses the extractor model because
 * compilation depends on the schema, not the model (checked 2026-10-17: the writer prompts' schemas
 * compiled on Sonnet 5.5 with the same results and token counts).
 *
 * <p>Skipped automatically when ANTHROPIC_API_KEY is not set. To skip it even with a key:
 * {@code ./mvnw test -DexcludedGroups=live}
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
class LlmSchemaCompileLiveTest {

    private static AnthropicTransport transport;

    @BeforeAll
    static void connect() {
        transport = new AnthropicTransport(TestFixtures.props(), TestFixtures.JSON);
    }

    @AfterAll
    static void disconnect() {
        transport.destroy();
    }

    @ParameterizedTest
    @MethodSource("com.bu.tailorsg.llm.LlmSchemaLimitsTest#allPrompts")
    void promptSchemaCompilesOnTheApi(String promptId) {
        PromptTemplate prompt = TestFixtures.prompts().get(promptId);
        JsonNode apiSchema = new ApiSchemaSanitizer().sanitize(TestFixtures.schemas().rawSchema(prompt.schemaPath()));

        // A tiny max_tokens: we only need the request accepted, not a complete answer.
        LlmReply reply = transport.send(new LlmRequest(promptId, TestFixtures.props().models().extractor(), "low", 16,
                "Reply with the smallest JSON object that matches the schema.", List.of(TextBlock.of("Go.")),
                apiSchema, false));

        assertThat(reply.stopReason()).isIn("end_turn", "max_tokens");
    }
}
