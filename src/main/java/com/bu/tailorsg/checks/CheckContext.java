package com.bu.tailorsg.checks;

import com.bu.tailorsg.coverletter.CoverLetterDraft;
import com.bu.tailorsg.export.ExportedFile;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.tailoring.TailoredResume;

import java.util.List;
import java.util.Map;

/**
 * Everything a rule may look at (spec 6: master, JobSpec, TailoredResume, rendered text, exported file,
 * options). Fields a mode does not have are null or empty, and rules skip themselves when their input is
 * missing. Built with {@link #builder(CheckMode, MasterResume, CheckThresholds)}; see {@link CheckContexts}
 * for the usual ways to build one.
 */
public final class CheckContext {

    private final CheckMode mode;
    private final MasterResume master;
    private final String masterText;
    private final JobSpec job;
    private final String text;
    private final String headerText;
    private final String titleLine;
    private final List<Bullet> bullets;
    private final List<DatedEntry> entries;
    private final List<String> headings;
    private final Integer pageCount;
    private final int pageTarget;
    private final boolean showWorkAuth;
    private final String workAuthLine;
    private final String spelling;
    private final ResumeDocument document;
    private final TailoredResume tailored;
    private final ExportedFile export;
    private final CoverLetterDraft coverLetter;
    private final Map<String, String> userNotes;
    private final CheckThresholds thresholds;

    private CheckContext(Builder b) {
        this.mode = b.mode;
        this.master = b.master;
        this.masterText = b.masterText != null ? b.masterText : MasterTexts.all(b.master);
        this.job = b.job;
        this.text = b.text == null ? "" : b.text;
        this.headerText = b.headerText == null ? "" : b.headerText;
        this.titleLine = b.titleLine == null ? "" : b.titleLine;
        this.bullets = List.copyOf(b.bullets);
        this.entries = List.copyOf(b.entries);
        this.headings = List.copyOf(b.headings);
        this.pageCount = b.pageCount;
        this.pageTarget = b.pageTarget;
        this.showWorkAuth = b.showWorkAuth;
        this.workAuthLine = b.workAuthLine;
        this.spelling = b.spelling != null ? b.spelling : (b.master.meta() == null ? "british" : b.master.meta().spelling());
        this.document = b.document;
        this.tailored = b.tailored;
        this.export = b.export;
        this.coverLetter = b.coverLetter;
        this.userNotes = Map.copyOf(b.userNotes);
        this.thresholds = b.thresholds;
    }

    public static Builder builder(CheckMode mode, MasterResume master, CheckThresholds thresholds) {
        return new Builder(mode, master, thresholds);
    }

    public CheckMode mode() { return mode; }
    public MasterResume master() { return master; }
    /** Every piece of text in the master, for "does this appear anywhere in the master?" questions. */
    public String masterText() { return masterText; }
    public JobSpec job() { return job; }
    /** The full text being checked (rendered resume, or the cover letter). */
    public String text() { return text; }
    public String headerText() { return headerText; }
    /** Title line and summary, for KW-03. */
    public String titleLine() { return titleLine; }
    public List<Bullet> bullets() { return bullets; }
    public List<DatedEntry> entries() { return entries; }
    public List<String> headings() { return headings; }
    /** Actual page count of an exported file, or null before export. */
    public Integer pageCount() { return pageCount; }
    public int pageTarget() { return pageTarget; }
    public boolean showWorkAuth() { return showWorkAuth; }
    public String workAuthLine() { return workAuthLine; }
    public String spelling() { return spelling; }
    public ResumeDocument document() { return document; }
    public TailoredResume tailored() { return tailored; }
    public ExportedFile export() { return export; }
    public CoverLetterDraft coverLetter() { return coverLetter; }
    /** Cover letter USER_NOTES: whyCompany, availability, mentionWorkPass. */
    public Map<String, String> userNotes() { return userNotes; }
    public CheckThresholds thresholds() { return thresholds; }

    /** Pages: the real count when exported, otherwise an estimate from body words. */
    public int pages() {
        if (pageCount != null) {
            return pageCount;
        }
        String body = document != null ? document.bodyText() : text;
        int perPage = Math.max(1, thresholds.pages().wordsPerPage());
        return Math.max(1, (TextTools.wordCount(body) + perPage - 1) / perPage);
    }

    public static final class Builder {
        private final CheckMode mode;
        private final MasterResume master;
        private final CheckThresholds thresholds;
        private String masterText;
        private JobSpec job;
        private String text;
        private String headerText;
        private String titleLine;
        private List<Bullet> bullets = List.of();
        private List<DatedEntry> entries = List.of();
        private List<String> headings = List.of();
        private Integer pageCount;
        private int pageTarget = 1;
        private boolean showWorkAuth;
        private String workAuthLine;
        private String spelling;
        private ResumeDocument document;
        private TailoredResume tailored;
        private ExportedFile export;
        private CoverLetterDraft coverLetter;
        private Map<String, String> userNotes = Map.of();

        private Builder(CheckMode mode, MasterResume master, CheckThresholds thresholds) {
            this.mode = mode;
            this.master = master;
            this.thresholds = thresholds;
        }

        public Builder masterText(String v) { masterText = v; return this; }
        public Builder job(JobSpec v) { job = v; return this; }
        public Builder text(String v) { text = v; return this; }
        public Builder headerText(String v) { headerText = v; return this; }
        public Builder titleLine(String v) { titleLine = v; return this; }
        public Builder bullets(List<Bullet> v) { bullets = v; return this; }
        public Builder entries(List<DatedEntry> v) { entries = v; return this; }
        public Builder headings(List<String> v) { headings = v; return this; }
        public Builder pageCount(Integer v) { pageCount = v; return this; }
        public Builder pageTarget(int v) { pageTarget = v; return this; }
        public Builder showWorkAuth(boolean v) { showWorkAuth = v; return this; }
        public Builder workAuthLine(String v) { workAuthLine = v; return this; }
        public Builder spelling(String v) { spelling = v; return this; }
        public Builder document(ResumeDocument v) { document = v; return this; }
        public Builder tailored(TailoredResume v) { tailored = v; return this; }
        public Builder export(ExportedFile v) { export = v; return this; }
        public Builder coverLetter(CoverLetterDraft v) { coverLetter = v; return this; }
        public Builder userNotes(Map<String, String> v) { userNotes = v == null ? Map.of() : v; return this; }

        public CheckContext build() {
            return new CheckContext(this);
        }
    }
}
