package com.bu.tailorsg.tailoring;

import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.support.TestResources;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TailoringServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void masterForTheModelDropsContactDetailsAndServerMeta() {
        JsonNode sample = JSON.readTree(TestResources.text("samples/master-sample.json"));
        JsonNode forModel = TailoringService.forModel(sample);
        assertThat(forModel.path("basics").has("email")).isFalse();
        assertThat(forModel.path("basics").has("profiles")).isFalse();
        assertThat(forModel.path("basics").path("summaryVariants").size()).isEqualTo(2);
        assertThat(forModel.path("meta").has("spelling")).isTrue();
        assertThat(sample.path("basics").has("email")).as("original untouched").isTrue();
    }

    @Test
    void tidyOnlyMakesMechanicalChanges() {
        TailoredResume raw = JSON.readValue(TestResources.text("fixtures/tailoring/llm-tailored.json"), TailoredResume.class);
        List<String> notes = new ArrayList<>();
        TailoredResume tidy = TailoringService.tidy(raw, new ApplicationOptions(1, false, true, false, null, null, null, false), notes);

        assertThat(tidy.summary().isPresent()).isFalse();
        assertThat(tidy.experience().get(1).bullets().get(2).text()).endsWith("3 hours a week");
        assertThat(tidy.experience().get(1).bullets().get(2).sourceIds()).containsExactly("work-1.h3");
        assertThat(notes).containsExactly("Removed the full stop at the end of 1 bullet.", "Dropped the summary: you did not ask for one.");
    }
}
