package com.bu.tailorsg.export;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationOptions;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckEngine;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.checks.ats.ParserRecall;
import com.bu.tailorsg.checks.ats.ParserRecall.FieldCheck;
import com.bu.tailorsg.checks.ats.ResumeParser;
import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.jobs.JobPosting;
import com.bu.tailorsg.render.ResumeDocument;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Export (spec 3: POST /api/applications/{id}/export?type=pdf|docx): render the tailored resume, read the
 * file back, run every check including the ATS round trip, then either save the file or refuse (P5).
 *
 * <p>The PDF is always rendered, because it is also where the DOCX's page count comes from (SG-09).
 */
@Service
public class ExportService {

    public static final Set<Category> EXPORT_CATEGORIES = EnumSet.of(Category.TRUTH, Category.SG, Category.ATS, Category.LINT, Category.KW);
    static final int MIN_REASON_CHARS = 10;

    private final ApplicationService applications;
    private final PdfRenderer pdf;
    private final DocxRenderer docx;
    private final CheckEngine engine;
    private final ResumeParser parser;
    private final ExportFileRepository files;
    private final TailorSgProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public ExportService(ApplicationService applications, PdfRenderer pdf, DocxRenderer docx, CheckEngine engine,
                         ResumeParser parser, ExportFileRepository files, TailorSgProperties props, JsonMapper json, Clock clock) {
        this.applications = applications;
        this.pdf = pdf;
        this.docx = docx;
        this.engine = engine;
        this.parser = parser;
        this.files = files;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /**
     * The outcome of rendering and checking one file.
     *
     * @param fields  what a typical parser recovers, against the expected values (ATS-10 table)
     * @param blocked true when there were ERRORs and no override reason
     */
    public record ExportCheck(ExportFile.Kind kind, ExportedFile.Type type, CheckReport report, List<FieldCheck> fields,
                              int pageCount, boolean blocked, String fileName, Instant at) {}

    /** A rendered file, before it is saved. */
    public record Rendered(ExportedFile file, String fileName, ExportCheck check) {}

    // ---------------------------------------------------------------- resume

    /** Renders and checks without saving (the "Check export" button). */
    public ExportCheck checkResume(long applicationId, ExportedFile.Type type) {
        Application a = applications.get(applicationId);
        Rendered r = renderResume(a, type, false);
        store(a, r.check());
        return r.check();
    }

    /**
     * Renders, checks and saves the resume. With ERRORs, saving needs an override reason of at least 10
     * characters, which is stored with the file and the application (P5).
     *
     * @throws ExportBlockedException when there are ERRORs and no reason
     */
    public ExportFile exportResume(long applicationId, ExportedFile.Type type, String overrideReason) {
        Application a = applications.get(applicationId);
        String reason = overrideReason == null ? "" : overrideReason.strip();
        Rendered r = renderResume(a, type, reason.length() < MIN_REASON_CHARS);
        return save(a, r, ExportFile.Kind.RESUME, reason);
    }

    Rendered renderResume(Application a, ExportedFile.Type type, boolean blockOnErrors) {
        ResumeDocument doc = applications.documentOf(a).orElseThrow(() -> new IllegalStateException("Tailor the resume first."));
        ApplicationOptions options = applications.optionsOf(a);
        JobPosting job = applications.jobOf(a);
        String company = job.getCompany() == null ? "Company" : job.getCompany();
        String fileName = baseName(doc.header().name()) + "_Resume_" + safe(company) + "." + type.name().toLowerCase(Locale.ROOT);
        String title = doc.header().name() + " - Resume - " + company;

        byte[] pdfBytes = pdf.render(ResumeHtml.of(doc, title));
        ExportedFile pdfFile = PdfInspector.inspect(pdfBytes);
        ExportedFile file = type == ExportedFile.Type.PDF ? pdfFile
                : DocxInspector.inspect(docx.render(doc, title), pdfFile.pageCount());

        CheckContext ctx = applications.resumeContext(a, CheckMode.EXPORT).orElseThrow()
                .pageCount(file.pageCount()).export(file).build();
        CheckReport report = engine.run(ctx, EXPORT_CATEGORIES);
        List<FieldCheck> fields = ParserRecall.compare(doc, parser.parse(file.extractedText()));
        boolean blocked = blockOnErrors && report.hasErrors();
        return new Rendered(file, fileName, new ExportCheck(ExportFile.Kind.RESUME, type, report, fields, file.pageCount(),
                blocked, fileName, clock.instant()));
    }

    // ---------------------------------------------------------------- shared by resume and cover letter

    /**
     * Saves a rendered file unless it is blocked. A resume's check is recorded on the application either way
     * (the Export tab shows it); a cover letter's checks are shown on the Cover letter tab instead.
     */
    public ExportFile save(Application a, Rendered r, ExportFile.Kind kind, String reason) {
        if (kind == ExportFile.Kind.RESUME) {
            store(a, r.check());
        }
        if (r.check().blocked()) {
            throw new ExportBlockedException(r.check());
        }
        boolean overridden = r.check().report().hasErrors();
        JobPosting job = applications.jobOf(a);
        String folder = safe(job.getCompany() == null ? "Company" : job.getCompany()) + "_"
                + safe(job.getTitle() == null ? "Job" : job.getTitle()) + "_" + LocalDate.now(clock.withZone(ZoneId.of("Asia/Singapore")));
        Path dir = root().resolve(folder);
        try {
            Files.createDirectories(dir);
            Path target = unique(dir, r.fileName());
            Files.write(target, r.file().bytes());
            ExportFile saved = files.save(new ExportFile(a.getId(), kind, r.file().type(), target.getFileName().toString(),
                    root().relativize(target).toString(), sha256(r.file().bytes()), r.file().bytes().length, r.file().pageCount(),
                    json.writeValueAsString(r.check().report()), overridden ? reason : null, clock.instant()));
            if (overridden) {
                a.setOverrideReason(reason, clock.instant());
            }
            if (a.getStatus() == Application.Status.DRAFT) {
                a.setStatus(Application.Status.EXPORTED, clock.instant());
            }
            applications.save(a);
            return saved;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + r.fileName(), e);
        }
    }

    public Optional<ExportCheck> lastCheckOf(Application a) {
        return Optional.ofNullable(a.getLastExportCheck()).map(s -> json.readValue(s, ExportCheck.class));
    }

    public List<ExportFile> filesOf(long applicationId) {
        return files.findByApplicationIdOrderByCreatedAtDescIdDesc(applicationId);
    }

    /** The file on disk, refusing anything outside the exports folder. */
    public Path pathOf(ExportFile f) {
        Path p = root().resolve(f.getRelativePath()).normalize();
        if (!p.startsWith(root())) {
            throw new IllegalStateException("Export path escapes the exports folder.");
        }
        return p;
    }

    public ExportFile file(long applicationId, long fileId) {
        return files.findById(fileId).filter(f -> f.getApplicationId() == applicationId)
                .orElseThrow(() -> new IllegalArgumentException("No export " + fileId + " for application " + applicationId + "."));
    }

    private void store(Application a, ExportCheck check) {
        a.setLastExportCheck(json.writeValueAsString(check), clock.instant());
        applications.save(a);
    }

    /** The folder name and file name pieces, shared with cover letters. */
    public String fileName(String personName, String what, String company, ExportedFile.Type type) {
        return baseName(personName) + "_" + what + "_" + safe(company == null ? "Company" : company) + "." + type.name().toLowerCase(Locale.ROOT);
    }

    public PdfRenderer pdfRenderer() {
        return pdf;
    }

    public DocxRenderer docxRenderer() {
        return docx;
    }

    private Path root() {
        return Path.of(props.storage().exportsDir()).toAbsolutePath().normalize();
    }

    private static Path unique(Path dir, String name) {
        Path target = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        for (int n = 2; Files.exists(target); n++) {
            target = dir.resolve(name.substring(0, dot) + "_" + n + name.substring(dot));
        }
        return target;
    }

    /** "Jordan Tan" becomes "Jordan_Tan" (spec 4: Firstname_Lastname_Resume_Company.pdf). */
    public static String baseName(String fullName) {
        return safe(fullName);
    }

    /** ASCII letters and digits only, words joined by "_", at most 60 characters. */
    public static String safe(String s) {
        String ascii = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String out = ascii.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (out.length() > 60) {
            out = out.substring(0, 60).replaceAll("_+$", "");
        }
        return out.isEmpty() ? "Untitled" : out;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
