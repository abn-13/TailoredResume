package com.bu.tailorsg.applications;

import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckContexts;
import com.bu.tailorsg.checks.CheckEngine;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.checks.ThresholdService;
import com.bu.tailorsg.jobs.JobPosting;
import com.bu.tailorsg.jobs.JobService;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.llm.JsonSchemaService;
import com.bu.tailorsg.llm.JsonSchemaService.SchemaViolation;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.StoredVersion;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.TailoredRenderer;
import com.bu.tailorsg.tailoring.TailoredResume;
import com.bu.tailorsg.tailoring.TailoringService;
import com.bu.tailorsg.tailoring.TailoringService.Tailoring;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applications (spec 3, "Applications"): create, tailor, check, and manual edits to the tailored resume.
 *
 * <p>Rendering and checks always use the <em>current</em> master, so a typo fixed in the master shows up in
 * every application, and a highlight deleted from the master makes the bullets citing it fail TRUTH-01.
 * The page warns when the master has changed since the resume was tailored.
 *
 * <p>{@link #tailor} is deliberately not {@code @Transactional}: the model call can take a minute or two,
 * and holding a database transaction open that long is bad practice even for H2. Each repository call runs
 * in its own short transaction instead.
 */
@Service
public class ApplicationService {

    public static final String TAILORED_SCHEMA = "schema/llm/tailored-resume.schema.json";
    public static final Set<Category> RESUME_CATEGORIES = EnumSet.of(Category.TRUTH, Category.SG, Category.LINT, Category.KW);
    static final int MAX_BULLET_CHARS = 600;

    private final ApplicationRepository repository;
    private final JobService jobs;
    private final MasterResumeService masters;
    private final TailoringService tailoring;
    private final TailoredRenderer renderer;
    private final CheckEngine engine;
    private final ThresholdService thresholds;
    private final JsonSchemaService schemas;
    private final JsonMapper json;
    private final Clock clock;

    public ApplicationService(ApplicationRepository repository, JobService jobs, MasterResumeService masters,
                              TailoringService tailoring, TailoredRenderer renderer, CheckEngine engine,
                              ThresholdService thresholds, JsonSchemaService schemas, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.jobs = jobs;
        this.masters = masters;
        this.tailoring = tailoring;
        this.renderer = renderer;
        this.engine = engine;
        this.thresholds = thresholds;
        this.schemas = schemas;
        this.json = json;
        this.clock = clock;
    }

    /** One row of the tracker (dashboard and GET /api/applications). */
    public record ApplicationRow(long id, long jobId, String title, String company, Application.Status status,
                                 Instant updatedAt, boolean tailored, int errors, int warnings) {}

    /** What the outcome of a tailoring run was, for the flash message. */
    public record TailorOutcome(Application application, CheckReport report, List<String> notes, BigDecimal costUsd,
                                int attempts) {}

    // ---------------------------------------------------------------- create and read

    @Transactional
    public Application create(long jobId, ApplicationOptions options) {
        jobs.get(jobId); // 404 for an unknown job
        return repository.save(new Application(jobId, json.writeValueAsString(options), clock.instant()));
    }

    @Transactional(readOnly = true)
    public Application get(long id) {
        return repository.findById(id).orElseThrow(() -> new ApplicationNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<ApplicationRow> list() {
        return repository.findAllByOrderByUpdatedAtDescIdDesc().stream().map(this::row).toList();
    }

    public ApplicationOptions optionsOf(Application a) {
        return json.readValue(a.getOptions(), ApplicationOptions.class);
    }

    public Optional<TailoredResume> tailoredOf(Application a) {
        return Optional.ofNullable(a.getTailoredResume()).map(s -> json.readValue(s, TailoredResume.class));
    }

    public Optional<CheckReport> reportOf(Application a) {
        return Optional.ofNullable(a.getCheckReport()).map(s -> json.readValue(s, CheckReport.class));
    }

    public Map<String, Integer> promptVersionsOf(Application a) {
        if (a.getPromptVersions() == null) {
            return Map.of();
        }
        return json.readValue(a.getPromptVersions(), new TypeReference<LinkedHashMap<String, Integer>>() {});
    }

    public JobSpec specOf(Application a) {
        return jobs.specOf(jobs.get(a.getJobPostingId()));
    }

    public JobPosting jobOf(Application a) {
        return jobs.get(a.getJobPostingId());
    }

    /** The rendered tailored resume, built from the current master (empty before the first tailoring). */
    public Optional<ResumeDocument> documentOf(Application a) {
        MasterResume master = masters.currentResume().orElse(null);
        Optional<TailoredResume> t = tailoredOf(a);
        if (master == null || t.isEmpty()) {
            return Optional.empty();
        }
        ApplicationOptions options = optionsOf(a);
        return Optional.of(renderer.render(master, t.get(), options.includeSummary(), options.showWorkAuth()));
    }

    // ---------------------------------------------------------------- tailoring and checks

    /**
     * Runs the tailoring prompt against the current master and this job, stores the result, then runs the
     * checks. Replaces any earlier tailored resume (including manual edits).
     *
     * @throws IllegalStateException when there is no master resume yet
     */
    public TailorOutcome tailor(long id) {
        Application a = get(id);
        StoredVersion master = masters.current()
                .orElseThrow(() -> new IllegalStateException("Create your master resume before tailoring."));
        Tailoring result = tailoring.tailor(master.json(), specOf(a), optionsOf(a));
        a.setTailored(json.writeValueAsString(result.resume()), master.versionNo(), clock.instant());
        setPromptVersion(a, TailoringService.PROMPT, result.llm().promptVersion());
        CheckReport report = runChecks(a);
        return new TailorOutcome(a, report, result.notes(), result.llm().costUsd(), result.llm().attempts());
    }

    /** Re-runs every check (spec 3: POST /api/applications/{id}/check), e.g. after manual edits. */
    public CheckReport recheck(long id) {
        return runChecks(get(id));
    }

    /** The check context for this application's tailored resume (also used by the fit and export services). */
    public Optional<CheckContext.Builder> resumeContext(Application a, CheckMode mode) {
        MasterResume master = masters.currentResume().orElse(null);
        Optional<TailoredResume> t = tailoredOf(a);
        if (master == null || t.isEmpty()) {
            return Optional.empty();
        }
        ApplicationOptions options = optionsOf(a);
        ResumeDocument doc = renderer.render(master, t.get(), options.includeSummary(), options.showWorkAuth());
        return Optional.of(CheckContexts.fromDocument(mode, master, doc, thresholds.current(), options.pageTarget(),
                options.showWorkAuth()).job(specOf(a)).tailored(t.get()));
    }

    private CheckReport runChecks(Application a) {
        CheckContext ctx = resumeContext(a, CheckMode.RESUME)
                .orElseThrow(() -> new IllegalStateException("Tailor the resume first.")).build();
        CheckReport report = engine.run(ctx, RESUME_CATEGORIES);
        a.setCheckReport(json.writeValueAsString(report), clock.instant());
        repository.save(a);
        return report;
    }

    // ---------------------------------------------------------------- manual edits (spec 7: inline edit, then re-check)

    /**
     * Replaces the whole tailored resume (spec 3: PUT /api/applications/{id}/resume). Validated against the
     * same schema as the model's output; provenance is then judged by the TRUTH checks, not here.
     */
    public CheckReport replaceResume(long id, JsonNode tailored) {
        List<SchemaViolation> errors = schemas.violations(TAILORED_SCHEMA, tailored);
        if (!errors.isEmpty()) {
            throw new TailoredResumeInvalidException(errors);
        }
        Application a = get(id);
        TailoredResume t = json.treeToValue(tailored, TailoredResume.class);
        a.setTailored(json.writeValueAsString(t), a.getMasterVersionNo(), clock.instant());
        return runChecks(a);
    }

    /** @param itemId a work/vol/project ID, or "summary" */
    public CheckReport editBullet(long id, String itemId, int index, String text) {
        String clean = requireText(text);
        Application a = get(id);
        TailoredResume t = requireTailored(a);
        TailoredResume edited = "summary".equals(itemId) ? t.withSummaryText(clean)
                : t.withBulletText(sectionOf(itemId), itemId, index, clean);
        return save(a, edited);
    }

    public CheckReport removeBullet(long id, String itemId, int index) {
        Application a = get(id);
        TailoredResume t = requireTailored(a);
        TailoredResume edited = "summary".equals(itemId) ? t.withSummaryText("") : t.withoutBullet(sectionOf(itemId), itemId, index);
        return save(a, edited);
    }

    /**
     * Adds a bullet citing one master highlight (keyword panel: "In your master, not used"). The highlight
     * must belong to the entry the bullet is added to, so the citation stays honest (TRUTH-01).
     */
    public CheckReport addBullet(long id, String itemId, String sourceId, String text) {
        String clean = requireText(text);
        MasterResume master = masters.currentResume().orElseThrow(() -> new IllegalStateException("No master resume."));
        if (sourceId == null || !sourceId.startsWith(itemId + ".h") || !master.highlightsById().containsKey(sourceId)) {
            throw new IllegalArgumentException("Highlight " + sourceId + " is not part of " + itemId + " in your master.");
        }
        Application a = get(id);
        TailoredResume edited = requireTailored(a).withAddedBullet(sectionOf(itemId), itemId,
                new TailoredResume.Bullet(clean, List.of(sourceId)));
        return save(a, edited);
    }

    /** New options (e.g. the cover letter notes). Takes effect at the next tailoring or letter. */
    @Transactional
    public Application updateOptions(long id, ApplicationOptions options) {
        Application a = get(id);
        a.setOptions(json.writeValueAsString(options), clock.instant());
        return repository.save(a);
    }

    @Transactional
    public Application setStatus(long id, Application.Status status) {
        Application a = get(id);
        a.setStatus(status, clock.instant());
        return repository.save(a);
    }

    /** Records which prompt version produced the current content (Phase 9: shown on the application). */
    public void setPromptVersion(Application a, String promptId, int version) {
        Map<String, Integer> versions = new LinkedHashMap<>(promptVersionsOf(a));
        versions.put(promptId, version);
        a.setPromptVersions(json.writeValueAsString(versions));
    }

    public Application save(Application a) {
        return repository.save(a);
    }

    private CheckReport save(Application a, TailoredResume edited) {
        a.setTailored(json.writeValueAsString(edited), a.getMasterVersionNo(), clock.instant());
        return runChecks(a);
    }

    private TailoredResume requireTailored(Application a) {
        return tailoredOf(a).orElseThrow(() -> new IllegalStateException("Tailor the resume first."));
    }

    static String sectionOf(String itemId) {
        if (itemId == null || !itemId.matches("(work|vol|proj)-\\d+")) {
            throw new IllegalArgumentException("Unknown entry " + itemId + ".");
        }
        return itemId.startsWith("proj-") ? "projects" : "experience";
    }

    private static String requireText(String text) {
        String clean = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("The text is empty. Use Remove to delete a bullet.");
        }
        if (clean.length() > MAX_BULLET_CHARS) {
            throw new IllegalArgumentException("Keep it under " + MAX_BULLET_CHARS + " characters.");
        }
        return clean;
    }

    private ApplicationRow row(Application a) {
        JobPosting job = jobs.get(a.getJobPostingId());
        CheckReport report = reportOf(a).orElse(null);
        int errors = report == null ? 0 : (int) report.errors().size();
        int warnings = report == null ? 0 : report.categories().stream().mapToInt(CheckReport.CategoryResult::warnings).sum();
        return new ApplicationRow(a.getId(), a.getJobPostingId(), job.getTitle(), job.getCompany(), a.getStatus(),
                a.getUpdatedAt(), a.getTailoredResume() != null, errors, warnings);
    }
}
