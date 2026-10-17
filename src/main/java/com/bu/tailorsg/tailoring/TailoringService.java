package com.bu.tailorsg.tailoring;

import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.llm.LlmTransport.UserBlock;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs the tailoring prompt (spec 5.3) and turns its output into a {@link TailoredResume}.
 *
 * <p>The model gets three blocks: MASTER first (marked cacheable, so re-tailoring for another job within
 * five minutes reads it from the prompt cache), then JOB, then OPTIONS. MASTER is sent without contact
 * details and server metadata: the renderer adds those from the master (P2), so the model never needs them.
 */
@Service
public class TailoringService {

    public static final String PROMPT = "tailor";

    private final ClaudeClient claude;
    private final JsonMapper json;

    public TailoringService(ClaudeClient claude, JsonMapper json) {
        this.claude = claude;
        this.json = json;
    }

    /** @param notes deterministic clean-ups applied to the model's output, shown to the user */
    public record Tailoring(TailoredResume resume, LlmResult llm, List<String> notes) {}

    public Tailoring tailor(JsonNode masterDocument, JobSpec job, ApplicationOptions options) {
        List<UserBlock> blocks = List.of(
                new TextBlock("<MASTER>\n" + json.writeValueAsString(forModel(masterDocument)) + "\n</MASTER>", true),
                TextBlock.of("<JOB>\n" + json.writeValueAsString(job) + "\n</JOB>"),
                TextBlock.of("<OPTIONS>\n" + json.writeValueAsString(Map.of(
                        "pageTarget", options.pageTarget(), "includeSummary", options.includeSummary())) + "\n</OPTIONS>"));
        LlmResult result = claude.call(PROMPT, blocks);
        List<String> notes = new ArrayList<>();
        TailoredResume resume = tidy(json.treeToValue(result.json(), TailoredResume.class), options, notes);
        return new Tailoring(resume, result, notes);
    }

    /** The master as the model sees it: no contact details, no server metadata (keeps spelling for rule 7). */
    public static JsonNode forModel(JsonNode document) {
        JsonNode copy = document.deepCopy();
        if (copy.get("basics") instanceof ObjectNode basics) {
            for (String field : List.of("email", "phone", "url", "profiles", "location")) { // the renderer adds these
                basics.remove(field);
            }
        }
        if (copy.get("meta") instanceof ObjectNode meta) {
            meta.remove("version");
            meta.remove("updatedAt");
        }
        return copy;
    }

    /**
     * Mechanical clean-ups that cannot change a fact: trim whitespace, drop a trailing full stop (rule 7),
     * drop empty bullets, and drop the summary when the user did not ask for one.
     */
    static TailoredResume tidy(TailoredResume t, ApplicationOptions options, List<String> notes) {
        int[] fixedStops = {0};
        var experience = t.experience().stream().map(e -> new TailoredResume.ExperienceItem(e.workId(),
                tidyBullets(e.bullets(), fixedStops))).filter(e -> !e.bullets().isEmpty()).toList();
        var projects = t.projects().stream().map(p -> new TailoredResume.ProjectItem(p.projectId(),
                tidyBullets(p.bullets(), fixedStops))).filter(p -> !p.bullets().isEmpty()).toList();
        if (fixedStops[0] > 0) {
            notes.add("Removed the full stop at the end of " + fixedStops[0] + " bullet" + (fixedStops[0] == 1 ? "" : "s") + ".");
        }
        var summary = t.summary();
        if (!options.includeSummary() && summary.isPresent()) {
            summary = new TailoredResume.Summary("", List.of());
            notes.add("Dropped the summary: you did not ask for one.");
        }
        return new TailoredResume(t.targetTitleLine() == null ? "" : t.targetTitleLine().strip(),
                new TailoredResume.Summary(summary.text().strip(), summary.sourceIds()), t.sectionOrder(),
                experience, projects, t.skillsLines(), t.omitted(), t.gapsNotAddressed());
    }

    private static List<TailoredResume.Bullet> tidyBullets(List<TailoredResume.Bullet> bullets, int[] fixedStops) {
        List<TailoredResume.Bullet> out = new ArrayList<>();
        for (TailoredResume.Bullet b : bullets) {
            String text = b.text() == null ? "" : b.text().strip();
            if (text.endsWith(".") && !text.endsWith("..")) {
                text = text.substring(0, text.length() - 1).strip();
                fixedStops[0]++;
            }
            if (!text.isEmpty()) {
                out.add(new TailoredResume.Bullet(text, b.sourceIds()));
            }
        }
        return out;
    }
}
