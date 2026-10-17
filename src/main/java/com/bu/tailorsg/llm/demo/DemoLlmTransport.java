package com.bu.tailorsg.llm.demo;

import com.bu.tailorsg.checks.TextTools;
import com.bu.tailorsg.checks.kw.KeywordAnalyzer;
import com.bu.tailorsg.checks.kw.KeywordMatcher.Term;
import com.bu.tailorsg.llm.LlmException;
import com.bu.tailorsg.llm.LlmTransport;
import com.bu.tailorsg.llm.TokenUsage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DEMO MODE ({@code tailorsg.llm.demo-mode=true}): answers every prompt locally, with no network call and no
 * cost, so the whole app can be tried (and screenshotted) without an API key.
 *
 * <p>The answers are deliberately simple and built only from the request itself: tailoring copies master
 * highlights verbatim and cites them; job extraction finds dictionary skills in the posting; the cover letter
 * strings master highlights together. They are schema-valid, so every check runs on them exactly as it would on
 * Claude's output. The UI shows a DEMO MODE banner the whole time. Never used unless switched on.
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "tailorsg.llm", name = "demo-mode", havingValue = "true")
public class DemoLlmTransport implements LlmTransport {

    public static final String MODEL = "demo-local";
    static final Pattern NONCE = Pattern.compile("\\b[0-9a-f]{6}\\b");
    static final Pattern PREFERRED_MARKER = Pattern.compile("(?i)(nice to have|preferred|good to have|bonus|a plus|advantage)");

    private final JsonMapper json;
    private final KeywordAnalyzer keywords;

    public DemoLlmTransport(JsonMapper json, KeywordAnalyzer keywords) {
        this.json = json;
        this.keywords = keywords;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public LlmReply send(LlmRequest request) {
        Map<String, String> blocks = blocks(request.userBlocks());
        JsonNode out = switch (request.promptId()) {
            case "ping" -> ping(blocks);
            case "jd-extract" -> jobSpec(blocks);
            case "tailor" -> tailor(blocks);
            case "fit-advice" -> fitAdvice(blocks);
            case "cover-letter" -> coverLetter(blocks);
            case "master-update" -> masterUpdate(blocks);
            default -> throw new LlmException("Demo mode cannot answer the '" + request.promptId()
                    + "' prompt (for a resume import, load the sample master instead). Set ANTHROPIC_API_KEY for the real thing.");
        };
        return new LlmReply(json.writeValueAsString(out), "end_turn", MODEL, TokenUsage.ZERO);
    }

    // ---------------------------------------------------------------- prompts

    private JsonNode ping(Map<String, String> blocks) {
        Matcher m = NONCE.matcher(blocks.getOrDefault("", ""));
        ObjectNode o = json.createObjectNode();
        o.put("ok", true);
        o.put("nonce", m.find() ? m.group() : "000000");
        o.put("message", "Demo mode: answered locally.");
        return o;
    }

    private JsonNode jobSpec(Map<String, String> blocks) {
        String text = blocks.getOrDefault("job_posting", blocks.getOrDefault("", ""));
        ObjectNode o = json.createObjectNode();
        String title = "";
        String company = "";
        Matcher structured = Pattern.compile("title \"([^\"]*)\", company \"([^\"]*)\"").matcher(blocks.getOrDefault("", ""));
        if (structured.find()) {
            title = structured.group(1);
            company = structured.group(2);
        }
        Matcher at = Pattern.compile("^\\s*(.{3,70}?)\\s+at\\s+([A-Z][\\w&'. -]{1,60}?)[.,\\n]").matcher(text);
        if (title.isBlank() && at.find()) {
            title = at.group(1).strip();
            company = company.isBlank() ? at.group(2).strip() : company;
        }
        if (title.isBlank()) {
            title = TextTools.clip(text.lines().filter(l -> !l.isBlank()).findFirst().orElse("Untitled role").strip(), 70);
        }
        o.put("title", title);
        o.put("company", company);
        o.put("location", text.contains("Singapore") ? "Singapore" : "");
        o.put("seniority", title.matches("(?i).*\\b(senior|lead|principal)\\b.*") ? "Senior" : title.matches("(?i).*\\bintern.*") ? "Intern" : "");
        o.put("employmentType", text.matches("(?is).*\\bintern(ship)?\\b.*") ? "Internship" : "Full-time");
        ObjectNode salary = o.putObject("salaryRange");
        salary.put("min", 0);
        salary.put("max", 0);
        salary.put("currency", "");
        Matcher marker = PREFERRED_MARKER.matcher(text);
        int split = marker.find() ? marker.start() : text.length();
        Set<String> required = new LinkedHashSet<>();
        Set<String> preferred = new LinkedHashSet<>();
        for (Term t : keywords.dictionaryTerms()) {
            if (t.foundIn(text.substring(0, split))) {
                required.add(t.label());
            } else if (t.foundIn(text.substring(split))) {
                preferred.add(t.label());
            }
        }
        skills(o.putArray("requiredSkills"), required);
        skills(o.putArray("preferredSkills"), preferred);
        ArrayNode facts = json.createArrayNode();
        for (String sentence : TextTools.sentences(text)) {
            if (sentence.startsWith("We ") || (!company.isBlank() && sentence.contains(company) && sentence.matches(".*\\d.*"))) {
                facts.add(sentence.replaceFirst("^We ", "").replaceAll("\\.$", "").strip());
            }
        }
        o.set("responsibilities", json.createArrayNode());
        o.set("qualifications", json.createArrayNode());
        o.set("keywordsRaw", json.createArrayNode());
        o.set("companyFacts", facts);
        o.put("applyInstructions", "");
        o.put("coverLetterRequested", text.toLowerCase(Locale.ROOT).contains("cover letter"));
        o.putArray("warnings").add("Demo mode: extracted by simple rules, not by Claude. Check every field.");
        return o;
    }

    private void skills(ArrayNode array, Set<String> terms) {
        for (String t : terms) {
            ObjectNode s = array.addObject();
            s.put("term", t);
            s.putArray("aliases");
        }
    }

    private JsonNode tailor(Map<String, String> blocks) {
        JsonNode master = json.readTree(blocks.get("MASTER"));
        JsonNode job = json.readTree(blocks.get("JOB"));
        JsonNode options = json.readTree(blocks.getOrDefault("OPTIONS", "{}"));
        List<String> jobTerms = jobTerms(job);
        ObjectNode o = json.createObjectNode();
        o.put("targetTitleLine", job.path("title").stringValue(""));
        ObjectNode summary = o.putObject("summary");
        JsonNode variant = master.path("basics").path("summaryVariants").path(0);
        boolean wantSummary = options.path("includeSummary").asBoolean(true) && !variant.isMissingNode();
        summary.put("text", wantSummary ? variant.path("text").stringValue("") : "");
        ArrayNode sumIds = summary.putArray("sourceIds");
        if (wantSummary) {
            sumIds.add(variant.path("id").stringValue(""));
        }
        ArrayNode order = o.putArray("sectionOrder");
        List.of("summary", "education", "experience", "projects", "skills").forEach(order::add);

        List<String> used = new ArrayList<>();
        ArrayNode experience = o.putArray("experience");
        int index = 0;
        for (JsonNode w : sortedByRecency(master.path("work"))) {
            boolean onePage = options.path("pageTarget").asInt(1) == 1;
            List<JsonNode> best = best(w.path("highlights"), jobTerms, index == 0 ? (onePage ? 3 : 4) : 2);
            if (!best.isEmpty()) {
                ObjectNode item = experience.addObject();
                item.put("workId", w.path("id").stringValue(""));
                bullets(item.putArray("bullets"), best, used);
            }
            index++;
        }
        ArrayNode projects = o.putArray("projects");
        List<JsonNode> ranked = new ArrayList<>();
        master.path("projects").values().forEach(ranked::add);
        ranked.sort(Comparator.comparingInt((JsonNode p) -> -score(p.toString(), jobTerms)));
        int maxProjects = options.path("pageTarget").asInt(1) == 2 ? 3 : 1; // spec 5.3 rule 9: fit the page target
        for (JsonNode p : ranked.subList(0, Math.min(maxProjects, ranked.size()))) {
            List<JsonNode> best = best(p.path("highlights"), jobTerms, 2);
            if (!best.isEmpty()) {
                ObjectNode item = projects.addObject();
                item.put("projectId", p.path("id").stringValue(""));
                bullets(item.putArray("bullets"), best, used);
            }
        }
        Map<String, ArrayNode> byCategory = new LinkedHashMap<>();
        ArrayNode lines = o.putArray("skillsLines");
        for (JsonNode s : master.path("skills").values()) {
            if (s.path("evidenceIds").isEmpty()) {
                continue; // an unevidenced skill is not shown
            }
            String category = s.path("category").stringValue("Skills");
            ArrayNode items = byCategory.computeIfAbsent(category, c -> {
                ObjectNode line = lines.addObject();
                line.put("category", c);
                return line.putArray("items");
            });
            ObjectNode item = items.addObject();
            item.put("name", s.path("name").stringValue(""));
            item.put("skillId", s.path("id").stringValue(""));
        }
        ArrayNode omitted = o.putArray("omitted");
        for (String section : List.of("work", "projects")) {
            master.path(section).values().forEach(e -> e.path("highlights").values().forEach(h -> {
                if (!used.contains(h.path("id").stringValue(""))) {
                    ObjectNode om = omitted.addObject();
                    om.put("id", h.path("id").stringValue(""));
                    om.put("reason", "Less relevant to this job (demo ranking by keyword overlap)");
                }
            }));
        }
        ArrayNode gaps = o.putArray("gapsNotAddressed");
        String masterText = master.toString();
        jobTerms.stream().filter(t -> !keywordIn(t, masterText)).forEach(gaps::add);
        return o;
    }

    private JsonNode fitAdvice(Map<String, String> blocks) {
        JsonNode facts = json.readTree(blocks.get("FACTS"));
        double required = Double.parseDouble(facts.path("requiredCoverage").stringValue("0%").replace("%", "")) / 100;
        List<String> gaps = new ArrayList<>();
        facts.path("gaps").values().forEach(g -> gaps.add(g.stringValue("")));
        List<String> requiredGaps = new ArrayList<>();
        facts.path("required").values().forEach(t -> {
            if ("GAP".equals(t.path("evidence").stringValue(""))) {
                requiredGaps.add(t.path("term").stringValue(""));
            }
        });
        String rec = required >= 0.8 && requiredGaps.isEmpty() ? "APPLY_NOW" : required >= 0.6 ? "APPLY_AND_ADDRESS_GAP"
                : gaps.size() <= 3 ? "BUILD_PROJECT_FIRST" : "SKIP";
        ObjectNode o = json.createObjectNode();
        o.put("recommendation", rec);
        o.put("headline", "Demo advice: your master covers " + facts.path("requiredCoverage").stringValue("") + " of the required skills"
                + (requiredGaps.isEmpty() ? "." : "; the required gap" + (requiredGaps.size() == 1 ? " is " : "s are ") + String.join(", ", requiredGaps) + "."));
        ArrayNode reasons = o.putArray("reasons");
        reasons.add("Required coverage " + facts.path("requiredCoverage").stringValue("") + ", preferred " + facts.path("preferredCoverage").stringValue("") + ".");
        facts.path("flags").values().forEach(f -> reasons.add(f.stringValue("")));
        ArrayNode strengths = o.putArray("strengths");
        facts.path("required").values().forEach(t -> {
            String id = t.path("sourceId").stringValue("");
            if ("EVIDENCED".equals(t.path("evidence").stringValue("")) && id.contains(".h") && strengths.size() < 4) {
                ObjectNode s = strengths.addObject();
                s.put("point", "Your master shows " + t.path("term").stringValue("") + " in real work.");
                s.putArray("sourceIds").add(id);
            }
        });
        ArrayNode plans = o.putArray("gapsToAddress");
        for (String g : (requiredGaps.isEmpty() ? gaps : requiredGaps).subList(0, Math.min(2, (requiredGaps.isEmpty() ? gaps : requiredGaps).size()))) {
            ObjectNode p = plans.addObject();
            p.put("term", g);
            p.put("howToAddress", "Say plainly in the cover letter that you have not used " + g + " in production yet, and point to the closest thing your master does show.");
        }
        ObjectNode project = o.putObject("projectSuggestion");
        boolean wantProject = !gaps.isEmpty() && !rec.equals("APPLY_NOW") && !rec.equals("SKIP");
        String target = wantProject ? (requiredGaps.isEmpty() ? gaps.get(0) : requiredGaps.get(0)) : "";
        project.put("title", wantProject ? "A small " + target + " demo" : "");
        ArrayNode targets = project.putArray("targetsGaps");
        if (wantProject) {
            targets.add(target);
        }
        project.put("description", wantProject ? "A one-feature service that uses " + target + " end to end, built on the stack your master already shows." : "");
        ArrayNode scope = project.putArray("scope");
        if (wantProject) {
            List.of("Set up " + target + " locally with Docker Compose", "One small feature that uses it end to end",
                    "Integration tests", "A README with a diagram and what you learnt").forEach(scope::add);
        }
        project.put("estimatedDays", wantProject ? 10 : 0);
        project.put("showcase", wantProject ? "Public GitHub repository with a README; add it to your master once it is done." : "");
        return o;
    }

    private JsonNode coverLetter(Map<String, String> blocks) {
        JsonNode master = json.readTree(blocks.get("MASTER"));
        JsonNode job = json.readTree(blocks.get("JOB"));
        JsonNode notes = json.readTree(blocks.getOrDefault("USER_NOTES", "{}"));
        String title = job.path("title").stringValue("this role");
        String company = TextTools.shortCompanyName(job.path("company").stringValue(""));
        List<String> jobTerms = jobTerms(job);

        List<ObjectNode> paragraphs = new ArrayList<>();
        StringBuilder open = new StringBuilder("I am applying for the " + title + " role" + (company.isBlank() ? "" : " at " + company) + ".");
        JsonNode fact = job.path("companyFacts").path(0);
        if (!fact.isMissingNode() && !company.isBlank()) {
            String f = fact.stringValue("");
            open.append(' ').append(company).append(' ').append(Character.toLowerCase(f.charAt(0))).append(f.substring(1)).append('.');
        }
        String why = notes.path("whyCompany").stringValue("");
        if (!why.isBlank()) {
            open.append(' ').append(why.strip()).append(why.strip().endsWith(".") ? "" : ".");
        }
        paragraphs.add(paragraph(open.toString(), List.of()));

        List<JsonNode> work = sortedByRecency(master.path("work"));
        for (int i = 0; i < Math.min(2, work.size()); i++) {
            JsonNode w = work.get(i);
            List<JsonNode> best = best(w.path("highlights"), jobTerms, i == 0 ? 3 : 2);
            if (best.isEmpty()) {
                continue;
            }
            String employer = TextTools.shortCompanyName(w.path("name").stringValue(""));
            StringBuilder p = new StringBuilder(i == 0 ? "At " + employer + ", I " : "Before that, as " + article(w.path("position").stringValue(""))
                    + " at " + employer + ", I ");
            List<String> ids = new ArrayList<>();
            for (int j = 0; j < best.size(); j++) {
                String text = best.get(j).path("text").stringValue("").replaceAll("\\.$", "");
                p.append(j == 0 ? lowerFirst(text) : (j == best.size() - 1 ? " Finally, I " : " I also ") + lowerFirst(text));
                p.append('.');
                ids.add(best.get(j).path("id").stringValue(""));
            }
            paragraphs.add(paragraph(p.toString(), ids));
        }

        StringBuilder close = new StringBuilder("I would welcome the chance to discuss how this experience fits the " + title + " role.");
        String availability = notes.path("availability").stringValue("");
        if (!availability.isBlank()) {
            close.append(" I am ").append(availability.strip().replaceAll("\\.$", "")).append('.');
        }
        if ("true".equals(notes.path("mentionWorkPass").stringValue(""))) {
            close.append(" I will need an Employment Pass to work in Singapore.");
        }
        paragraphs.add(paragraph(close.toString(), List.of()));

        ObjectNode o = json.createObjectNode();
        o.put("recipientLine", "Dear Hiring Manager,");
        o.put("subject", "Application for " + title);
        ArrayNode ps = o.putArray("paragraphs");
        paragraphs.forEach(ps::add);
        o.put("signOff", "Yours sincerely,");
        o.put("wordCount", TextTools.wordCount(String.join(" ", paragraphs.stream().map(p -> p.path("text").stringValue("")).toList())));
        return o;
    }

    private JsonNode masterUpdate(Map<String, String> blocks) {
        JsonNode master = json.readTree(blocks.get("MASTER"));
        String text = blocks.getOrDefault("USER_TEXT", "").strip();
        String first = TextTools.sentences(text).stream().findFirst().orElse(text).strip().replaceAll("\\.$", "");
        first = first.replaceFirst("^(?i)(in my (internship|job|role|project),?\\s+)", "").replaceFirst("^I\\s+", "");
        first = first.isEmpty() ? text : Character.toUpperCase(first.charAt(0)) + first.substring(1);
        ObjectNode o = json.createObjectNode();
        ArrayNode patch = o.putArray("patch");
        List<JsonNode> work = sortedByRecency(master.path("work"));
        if (!work.isEmpty()) {
            int index = 0;
            String targetId = work.get(0).path("id").stringValue("");
            for (int i = 0; i < master.path("work").size(); i++) {
                if (master.path("work").path(i).path("id").stringValue("").equals(targetId)) {
                    index = i;
                }
            }
            ObjectNode highlight = json.createObjectNode();
            highlight.put("id", "NEW-1");
            highlight.put("text", first);
            ObjectNode op = patch.addObject();
            op.put("op", "add");
            op.put("path", "/work/" + index + "/highlights/-");
            op.put("from", "");
            op.put("valueJson", json.writeValueAsString(highlight));
            o.put("summary", "Demo: add one highlight to " + work.get(0).path("position").stringValue("") + " at "
                    + work.get(0).path("name").stringValue("") + ", using your first sentence.");
        } else {
            o.put("summary", "Demo: nothing to add to (no work entries yet).");
        }
        ArrayNode questions = o.putArray("questions");
        if (!text.matches("(?s).*\\d.*")) {
            questions.add("What was the measurable result, such as time saved or errors reduced?");
        }
        questions.add("Demo mode: this highlight is your own first sentence; edit it in the patch if needed.");
        return o;
    }

    // ---------------------------------------------------------------- helpers

    /** User blocks by tag: "<MASTER>...</MASTER>" becomes MASTER -> content; untagged text goes under "". */
    private static Map<String, String> blocks(List<UserBlock> userBlocks) {
        Map<String, String> out = new LinkedHashMap<>();
        Pattern tagged = Pattern.compile("(?s)^\\s*<([A-Za-z_]+)>\\n?(.*?)\\n?</\\1>\\s*$");
        for (UserBlock b : userBlocks) {
            if (b instanceof TextBlock t) {
                Matcher m = tagged.matcher(t.text());
                if (m.find()) {
                    out.put(m.group(1), m.group(2));
                } else {
                    out.merge("", t.text(), (a, c) -> a + "\n" + c);
                }
            }
        }
        return out;
    }

    private static List<String> jobTerms(JsonNode job) {
        List<String> terms = new ArrayList<>();
        job.path("requiredSkills").values().forEach(s -> terms.add(s.path("term").stringValue("")));
        job.path("preferredSkills").values().forEach(s -> terms.add(s.path("term").stringValue("")));
        terms.removeIf(String::isBlank);
        return terms;
    }

    private static List<JsonNode> sortedByRecency(JsonNode entries) {
        List<JsonNode> list = new ArrayList<>();
        entries.values().forEach(list::add);
        list.sort(Comparator.comparing((JsonNode e) -> e.path("endDate").isMissingNode() || e.path("endDate").isNull()
                ? "9999" : e.path("endDate").stringValue("")).reversed());
        return list;
    }

    private static List<JsonNode> best(JsonNode highlights, List<String> jobTerms, int max) {
        List<JsonNode> list = new ArrayList<>();
        highlights.values().forEach(list::add);
        List<JsonNode> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparingInt((JsonNode h) -> -score(h.toString(), jobTerms)));
        List<JsonNode> chosen = sorted.subList(0, Math.min(max, sorted.size()));
        return list.stream().filter(chosen::contains).toList(); // keep the master's order
    }

    private static int score(String text, List<String> terms) {
        return (int) terms.stream().filter(t -> keywordIn(t, text)).count();
    }

    private static boolean keywordIn(String term, String text) {
        return TextTools.containsPhrase(text, term) || TextTools.containsPhrase(text, term.replaceAll("s$", ""));
    }

    private void bullets(ArrayNode array, List<JsonNode> highlights, List<String> used) {
        for (JsonNode h : highlights) {
            ObjectNode b = array.addObject();
            b.put("text", h.path("text").stringValue(""));
            b.putArray("sourceIds").add(h.path("id").stringValue(""));
            used.add(h.path("id").stringValue(""));
        }
    }

    private ObjectNode paragraph(String text, List<String> ids) {
        ObjectNode p = json.createObjectNode();
        p.put("text", text);
        ArrayNode a = p.putArray("sourceIds");
        ids.forEach(a::add);
        return p;
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty() || (s.length() > 1 && Character.isUpperCase(s.charAt(1)))) {
            return s; // keep acronyms such as "AWS" or "REST"
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String article(String noun) {
        return (noun.matches("(?i)^[aeiou].*") ? "an " : "a ") + noun;
    }
}
