package com.bu.tailorsg.checks;

import com.bu.tailorsg.coverletter.CoverLetterDraft;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.tailoring.TailoredResume;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every rule has a passing and a failing fixture (spec 0: "Every checker rule gets unit tests with a passing
 * and a failing fixture"). One JSON file per case in src/test/resources/fixtures/checks:
 *
 * <pre>
 * { "rule": "SG-01", "expect": "ERROR", "expectMessage": "optional substring", "mode": "RESUME",
 *   "pass": { context... }, "fail": { context... } }
 * </pre>
 *
 * The failing context must produce a finding of the expected severity (and message); the passing context must
 * not. Context fields: text, headerText, titleLine, bullets[{text, kind, current, itemId, sourceIds}],
 * entries[{section, id, start, end}], pageCount, pageTarget, job{title, required[], preferred[], companyFacts[]},
 * tailored (TailoredResume JSON), document (ResumeDocument JSON), coverLetter (CoverLetterDraft JSON), userNotes{}, master (inline JSON).
 * The master defaults to the fake sample.
 */
@SpringBootTest
class CheckRuleFixturesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private CheckEngine engine;
    @Autowired private ThresholdService thresholds;

    @TestFactory
    Stream<DynamicContainer> everyRuleHasAPassingAndAFailingFixture() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:fixtures/checks/*.json");
        assertThat(files).isNotEmpty();
        return Arrays.stream(files).sorted(Comparator.comparing(Resource::getFilename)).map(this::container);
    }

    @TestFactory
    Stream<DynamicTest> everyRegisteredRuleHasFixtures() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:fixtures/checks/*.json");
        List<String> covered = new ArrayList<>();
        for (Resource f : files) {
            covered.add(JSON.readTree(f.getContentAsString(StandardCharsets.UTF_8)).path("rule").stringValue(""));
        }
        // Rules that need a real exported file are tested in dedicated classes instead (SG-04, ATS-*).
        return engine.checks().stream()
                .filter(c -> !c.id().equals("SG-04") && !c.id().startsWith("ATS-"))
                .map(c -> DynamicTest.dynamicTest(c.id() + " has a fixture", () -> assertThat(covered).contains(c.id())));
    }

    private DynamicContainer container(Resource file) {
        try {
            JsonNode fixture = JSON.readTree(file.getContentAsString(StandardCharsets.UTF_8));
            String rule = fixture.path("rule").stringValue();
            Severity expect = Severity.valueOf(fixture.path("expect").stringValue("ERROR"));
            String expectMessage = fixture.path("expectMessage").stringValue("");
            CheckMode mode = CheckMode.valueOf(fixture.path("mode").stringValue("RESUME"));
            Check check = engine.checks().stream().filter(c -> c.id().equals(rule)).findFirst()
                    .orElseThrow(() -> new AssertionError("No rule " + rule + " for " + file.getFilename()));
            return DynamicContainer.dynamicContainer(file.getFilename(), Stream.of(
                    DynamicTest.dynamicTest("passes", () -> assertThat(relevant(check.run(context(mode, fixture.path("pass"))), expect, expectMessage))
                            .as(rule + " pass fixture").isEmpty()),
                    DynamicTest.dynamicTest("fails with " + expect, () -> assertThat(relevant(check.run(context(mode, fixture.path("fail"))), expect, expectMessage))
                            .as(rule + " fail fixture").isNotEmpty())));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Finding> relevant(List<Finding> findings, Severity severity, String message) {
        return findings.stream().filter(f -> f.severity() == severity && f.message().contains(message)).toList();
    }

    CheckContext context(CheckMode mode, JsonNode c) {
        MasterResume master = c.has("master") ? JSON.treeToValue(c.get("master"), MasterResume.class) : sample();
        CheckContext.Builder b = CheckContext.builder(mode, master, thresholds.current())
                .text(text(c.path("text")))
                .headerText(c.path("headerText").stringValue(""))
                .titleLine(c.path("titleLine").stringValue(""))
                .pageTarget(c.path("pageTarget").asInt(1))
                .showWorkAuth(c.path("showWorkAuth").asBoolean(false));
        if (c.has("pageCount")) {
            b.pageCount(c.get("pageCount").asInt());
        }
        if (c.has("workAuthLine")) {
            b.workAuthLine(c.get("workAuthLine").stringValue());
        }
        List<Bullet> bullets = new ArrayList<>();
        int i = 0;
        for (JsonNode bn : c.path("bullets").values()) {
            String itemId = bn.path("itemId").stringValue("work-1");
            List<String> sources = new ArrayList<>();
            bn.path("sourceIds").values().forEach(s -> sources.add(s.stringValue()));
            bullets.add(new Bullet("b-" + itemId + "-" + i, itemId + " bullet " + (i + 1),
                    Bullet.Kind.valueOf(bn.path("kind").stringValue("EXPERIENCE")), itemId, i,
                    bn.path("current").asBoolean(false), bn.path("text").stringValue(), sources));
            i++;
        }
        b.bullets(bullets);
        List<DatedEntry> entries = new ArrayList<>();
        for (JsonNode e : c.path("entries").values()) {
            entries.add(new DatedEntry(e.path("section").stringValue(), e.path("id").stringValue(), e.path("id").stringValue(),
                    e.path("start").isNull() ? null : e.path("start").stringValue(null),
                    e.path("end").isNull() ? null : e.path("end").stringValue(null)));
        }
        b.entries(entries);
        if (c.has("job")) {
            JsonNode j = c.get("job");
            JobSpec spec = JobSpec.ofTerms(j.path("title").stringValue(null), strings(j.path("required")), strings(j.path("preferred")));
            spec = new JobSpec(spec.title(), j.path("company").stringValue(null), null, null, null, null, spec.requiredSkills(),
                    spec.preferredSkills(), List.of(), List.of(), List.of(), strings(j.path("companyFacts")), null, false, List.of());
            b.job(spec);
        }
        if (c.has("document")) {
            b.document(JSON.treeToValue(c.get("document"), ResumeDocument.class));
        }
        if (c.has("tailored")) {
            b.tailored(JSON.treeToValue(c.get("tailored"), TailoredResume.class));
        }
        if (c.has("coverLetter")) {
            CoverLetterDraft cl = JSON.treeToValue(c.get("coverLetter"), CoverLetterDraft.class);
            b.coverLetter(cl);
            if (!c.has("text")) {
                b.text(cl.fullText());
            }
        }
        Map<String, String> notes = new HashMap<>();
        c.path("userNotes").properties().forEach(e -> notes.put(e.getKey(), e.getValue().asString()));
        b.userNotes(notes);
        return b.build();
    }

    private static String text(JsonNode node) {
        if (node.isArray()) {
            return String.join("\n", strings(node));
        }
        return node.stringValue("");
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.values().forEach(v -> out.add(v.stringValue()));
        return out;
    }

    static MasterResume sample() {
        try {
            return JSON.readValue(new DefaultResourceLoader().getResource("classpath:samples/master-sample.json")
                    .getContentAsString(StandardCharsets.UTF_8), MasterResume.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
