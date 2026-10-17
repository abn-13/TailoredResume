package com.bu.tailorsg.coverletter;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckEngine;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.checks.TextTools;
import com.bu.tailorsg.checks.ThresholdService;
import com.bu.tailorsg.checks.cl.DashFixer;
import com.bu.tailorsg.coverletter.CoverLetterDraft.Paragraph;
import com.bu.tailorsg.export.DocxInspector;
import com.bu.tailorsg.export.ExportFile;
import com.bu.tailorsg.export.ExportService;
import com.bu.tailorsg.export.ExportedFile;
import com.bu.tailorsg.export.PdfInspector;
import com.bu.tailorsg.jobs.JobPosting;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.StoredVersion;
import com.bu.tailorsg.tailoring.TailoringService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cover letters (spec 5.4, Phase 7): write with the writer model, check with CL and TRUTH rules (the TRUTH
 * rules per paragraph, against the cited highlights plus the job's company facts and the user's notes),
 * edit, fix dashes, and export as PDF or DOCX in the resume's font.
 */
@Service
public class CoverLetterService {

    public static final String PROMPT = "cover-letter";
    public static final Set<Category> LETTER_CATEGORIES = EnumSet.of(Category.TRUTH, Category.CL);
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private final ApplicationService applications;
    private final MasterResumeService masters;
    private final ClaudeClient claude;
    private final CheckEngine engine;
    private final ThresholdService thresholds;
    private final ExportService exports;
    private final JsonMapper json;
    private final Clock clock;

    public CoverLetterService(ApplicationService applications, MasterResumeService masters, ClaudeClient claude, CheckEngine engine,
                              ThresholdService thresholds, ExportService exports, JsonMapper json, Clock clock) {
        this.applications = applications;
        this.masters = masters;
        this.claude = claude;
        this.engine = engine;
        this.thresholds = thresholds;
        this.exports = exports;
        this.json = json;
        this.clock = clock;
    }

    /** @param notes deterministic clean-ups applied to the model's output */
    public record Written(CoverLetterDraft draft, CheckReport report, List<String> notes, BigDecimal costUsd) {}

    /** One paragraph's dash fix, for the before/after view. */
    public record DashChange(int index, String before, String after) {}

    // ---------------------------------------------------------------- write and check

    public Written write(long applicationId) {
        Application a = applications.get(applicationId);
        StoredVersion master = masters.current().orElseThrow(() -> new IllegalStateException("Create your master resume first."));
        JobSpec job = applications.specOf(a);
        LlmResult result = claude.call(PROMPT, List.of(
                new TextBlock("<MASTER>\n" + json.writeValueAsString(TailoringService.forModel(master.json())) + "\n</MASTER>", true),
                TextBlock.of("<JOB>\n" + json.writeValueAsString(job) + "\n</JOB>"),
                TextBlock.of("<USER_NOTES>\n" + json.writeValueAsString(userNotes(applications.optionsOf(a))) + "\n</USER_NOTES>")));
        List<String> notes = new ArrayList<>();
        CoverLetterDraft draft = tidy(json.treeToValue(result.json(), CoverLetterDraft.class), job,
                masters.asResume(master.json()).orElseThrow().basics().name(), notes);
        a.setCoverLetter(json.writeValueAsString(draft), clock.instant());
        applications.setPromptVersion(a, PROMPT, result.promptVersion());
        CheckReport report = check(a);
        return new Written(draft, report, notes, result.costUsd());
    }

    /**
     * Mechanical clean-ups: the subject names the job title exactly (spec 5.4), the sign-off does not repeat
     * the name (the renderer prints it from the master), and the word count is recounted.
     */
    static CoverLetterDraft tidy(CoverLetterDraft d, JobSpec job, String name, List<String> notes) {
        String subject = d.subject() == null ? "" : d.subject().strip();
        if (job.title() != null && !job.title().isBlank() && !subject.contains(job.title().strip())) {
            notes.add("Subject set to the job title as posted.");
            subject = "Application for " + job.title().strip();
        }
        String signOff = d.signOff() == null || d.signOff().isBlank() ? "Yours sincerely," : d.signOff().strip();
        if (name != null && signOff.contains(name)) {
            signOff = signOff.replace(name, "").strip();
        }
        List<Paragraph> paragraphs = d.paragraphs().stream().filter(p -> p.text() != null && !p.text().isBlank())
                .map(p -> new Paragraph(p.text().strip(), p.sourceIds())).toList();
        int words = TextTools.wordCount(String.join("\n", paragraphs.stream().map(Paragraph::text).toList()));
        return new CoverLetterDraft(d.recipientLine() == null ? "Dear Hiring Manager," : d.recipientLine().strip(), subject,
                paragraphs, signOff, words);
    }

    public Optional<CoverLetterDraft> draftOf(Application a) {
        return Optional.ofNullable(a.getCoverLetter()).map(s -> json.readValue(s, CoverLetterDraft.class));
    }

    public Optional<CheckReport> reportOf(Application a) {
        return Optional.ofNullable(a.getCoverLetterReport()).map(s -> json.readValue(s, CheckReport.class));
    }

    public CheckReport check(Application a) {
        CoverLetterDraft draft = draftOf(a).orElseThrow(() -> new IllegalStateException("Write the cover letter first."));
        CheckReport report = engine.run(context(a, draft), LETTER_CATEGORIES);
        a.setCoverLetterReport(json.writeValueAsString(report), clock.instant());
        applications.save(a);
        return report;
    }

    private CheckContext context(Application a, CoverLetterDraft draft) {
        MasterResume master = masters.currentResume().orElseThrow(() -> new IllegalStateException("No master resume."));
        return CheckContext.builder(CheckMode.COVER_LETTER, master, thresholds.current())
                .job(applications.specOf(a)).coverLetter(draft).text(draft.fullText())
                .userNotes(userNotes(applications.optionsOf(a))).build();
    }

    static Map<String, String> userNotes(ApplicationOptions o) {
        Map<String, String> notes = new LinkedHashMap<>();
        notes.put("whyCompany", o.whyCompany() == null ? "" : o.whyCompany());
        notes.put("availability", o.availability() == null ? "" : o.availability());
        notes.put("mentionWorkPass", String.valueOf(o.mentionWorkPass()));
        notes.put("tone", o.tone());
        return notes;
    }

    // ---------------------------------------------------------------- edits

    public CheckReport editParagraph(long applicationId, int index, String text) {
        String clean = text == null ? "" : text.strip().replaceAll("[ \\t]+", " ");
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("A paragraph cannot be empty.");
        }
        Application a = applications.get(applicationId);
        CoverLetterDraft d = draftOf(a).orElseThrow(() -> new IllegalStateException("Write the cover letter first."));
        if (index < 0 || index >= d.paragraphs().size()) {
            throw new IllegalArgumentException("No paragraph " + (index + 1) + ".");
        }
        List<Paragraph> ps = new ArrayList<>(d.paragraphs());
        ps.set(index, new Paragraph(clean, ps.get(index).sourceIds()));
        return save(a, d.withParagraphs(ps));
    }

    /** CL-01 auto-fix preview: each paragraph whose dashes would change. */
    public List<DashChange> dashChanges(CoverLetterDraft d) {
        List<DashChange> out = new ArrayList<>();
        for (int i = 0; i < d.paragraphs().size(); i++) {
            String before = d.paragraphs().get(i).text();
            if (DashFixer.hasDash(before)) {
                out.add(new DashChange(i, before, DashFixer.fix(before)));
            }
        }
        return out;
    }

    public CheckReport fixDashes(long applicationId) {
        Application a = applications.get(applicationId);
        CoverLetterDraft d = draftOf(a).orElseThrow(() -> new IllegalStateException("Write the cover letter first."));
        List<Paragraph> ps = d.paragraphs().stream().map(p -> new Paragraph(DashFixer.fix(p.text()), p.sourceIds())).toList();
        CoverLetterDraft fixed = new CoverLetterDraft(DashFixer.fix(d.recipientLine()), DashFixer.fix(d.subject()), ps,
                DashFixer.fix(d.signOff()), d.wordCount());
        return save(a, fixed);
    }

    private CheckReport save(Application a, CoverLetterDraft d) {
        int words = TextTools.wordCount(d.bodyText());
        a.setCoverLetter(json.writeValueAsString(new CoverLetterDraft(d.recipientLine(), d.subject(), d.paragraphs(), d.signOff(), words)),
                clock.instant());
        return check(a);
    }

    // ---------------------------------------------------------------- layout and export

    /** The letter as the reader sees it: master contact line, today's date, recipient block from the job. */
    public Optional<LetterDocument> documentOf(Application a) {
        Optional<CoverLetterDraft> draft = draftOf(a);
        Optional<MasterResume> master = masters.currentResume();
        if (draft.isEmpty() || master.isEmpty()) {
            return Optional.empty();
        }
        var b = master.get().basics();
        List<String> contact = new ArrayList<>();
        contact.add(b.email());
        if (b.phone() != null) {
            contact.add(b.phone());
        }
        JobPosting job = applications.jobOf(a);
        List<String> recipient = new ArrayList<>(List.of("Hiring Manager"));
        if (job.getCompany() != null) {
            recipient.add(job.getCompany());
        }
        CoverLetterDraft d = draft.get();
        return Optional.of(new LetterDocument(b.name(), String.join(" | ", contact),
                LocalDate.now(clock.withZone(ZoneId.of("Asia/Singapore"))).format(DATE), recipient, d.subject(), d.recipientLine(),
                d.paragraphs().stream().map(Paragraph::text).toList(), d.signOff()));
    }

    /**
     * Renders, checks and saves the letter (P5 applies: ERRORs need a typed reason).
     *
     * @throws com.bu.tailorsg.export.ExportBlockedException when blocked
     */
    public ExportFile export(long applicationId, ExportedFile.Type type, String overrideReason) {
        Application a = applications.get(applicationId);
        CoverLetterDraft draft = draftOf(a).orElseThrow(() -> new IllegalStateException("Write the cover letter first."));
        LetterDocument letter = documentOf(a).orElseThrow();
        JobPosting job = applications.jobOf(a);
        String title = letter.name() + " - Cover letter - " + (job.getCompany() == null ? "" : job.getCompany());
        byte[] pdf = exports.pdfRenderer().render(LetterHtml.of(letter, title));
        ExportedFile pdfFile = PdfInspector.inspect(pdf);
        ExportedFile file = type == ExportedFile.Type.PDF ? pdfFile
                : DocxInspector.inspect(exports.docxRenderer().renderParagraphs(letter.blocks(), title, letter.name()), pdfFile.pageCount());
        CheckReport report = engine.run(context(a, draft), LETTER_CATEGORIES);
        String reason = overrideReason == null ? "" : overrideReason.strip();
        boolean blocked = report.hasErrors() && reason.length() < 10;
        String fileName = exports.fileName(letter.name(), "Cover_Letter", job.getCompany(), type);
        var check = new ExportService.ExportCheck(ExportFile.Kind.COVER_LETTER, type, report, List.of(), file.pageCount(), blocked,
                fileName, clock.instant());
        return exports.save(a, new ExportService.Rendered(file, fileName, check), ExportFile.Kind.COVER_LETTER, reason);
    }
}
