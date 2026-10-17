package com.bu.tailorsg.checks.ats;

import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckContexts;
import com.bu.tailorsg.checks.CheckEngine;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.ThresholdService;
import com.bu.tailorsg.export.DocxInspector;
import com.bu.tailorsg.export.DocxRenderer;
import com.bu.tailorsg.export.ExportedFile;
import com.bu.tailorsg.export.ExportedFile.FileFacts;
import com.bu.tailorsg.export.ExportedFile.FontUse;
import com.bu.tailorsg.export.PdfInspector;
import com.bu.tailorsg.export.PdfRenderer;
import com.bu.tailorsg.export.ResumeHtml;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeRenderer;
import com.bu.tailorsg.support.TestResources;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ATS-01..08, ATS-10 and SG-04 need a real or synthetic exported file, so they are tested here instead of
 * with JSON fixtures: the real PDF and DOCX of the sample master must pass, and a broken variant of the
 * extracted text or file facts must fail each rule.
 */
@SpringBootTest
class AtsChecksTest {

    static final MasterResume SAMPLE = JsonMapper.builder().build().readValue(TestResources.text("samples/master-sample.json"), MasterResume.class);
    static final ResumeDocument DOC = new ResumeRenderer().renderMaster(SAMPLE);
    static final ExportedFile PDF = PdfInspector.inspect(new PdfRenderer().render(ResumeHtml.of(DOC, "t")));

    @Autowired private CheckEngine engine;
    @Autowired private ThresholdService thresholds;

    @Test
    void theRealPdfAndDocxPassEveryAtsRule() {
        assertThat(ats(PDF).category(Category.ATS).findings()).isEmpty();
        ExportedFile docx = DocxInspector.inspect(new DocxRenderer().render(DOC, "t"), PDF.pageCount());
        assertThat(ats(docx).category(Category.ATS).findings()).isEmpty();
    }

    @Test
    void ats01ImageOnlyOutput() {
        assertThat(errors(ats(withText("Jordan Tan")), "ATS-01")).isNotEmpty();
    }

    @Test
    void ats02NameNotAtTheTopOrEmailBroken() {
        assertThat(errors(ats(withText("Summary\nExperience\nSkills\n" + PDF.extractedText())), "ATS-02")).isNotEmpty();
        assertThat(errors(ats(withText(PDF.extractedText().replace("jordan.tan@example.com", "jordan.tan @example.com"))), "ATS-02")).isNotEmpty();
    }

    @Test
    void ats03HeadingMissing() {
        assertThat(errors(ats(withText(PDF.extractedText().replace("\nProjects\n", "\nPROJ\n"))), "ATS-03")).isNotEmpty();
    }

    @Test
    void ats04DatesFarFromTheirJob() {
        // What the float:right layout produced: the dates extracted at the top of the page.
        String broken = "May 2026 - Present\n" + "x ".repeat(300) + PDF.extractedText().replace(" May 2026 - Present", "");
        assertThat(errors(ats(withText(broken)), "ATS-04")).extracting(Finding::message)
                .anySatisfy(m -> assertThat(m).contains("Software Engineering Intern", "May 2026"));
    }

    @Test
    void ats05ScrambledReadingOrder() {
        List<String> lines = new ArrayList<>(PDF.extractedText().lines().toList());
        Collections.reverse(lines.subList(3, lines.size()));
        assertThat(ats(withText(String.join("\n", lines))).category(Category.ATS).findings())
                .anySatisfy(f -> assertThat(f.checkId()).isEqualTo("ATS-05"));
        assertThat(AtsChecks.Ats05ReadingOrder.similarity(List.of("a", "b", "c"), List.of("a", "b", "c"))).isEqualTo(1.0);
        assertThat(AtsChecks.Ats05ReadingOrder.similarity(List.of("a", "b", "c", "d"), List.of("d", "c", "b", "a"))).isEqualTo(0.25);
    }

    @Test
    void ats06DocxWithATableAndAFooter() throws Exception {
        XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(new DocxRenderer().render(DOC, "t")));
        doc.createTable(2, 2).getRow(0).getCell(0).setText("Skills");
        doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Page 1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.write(out);
        ExportedFile docx = DocxInspector.inspect(out.toByteArray(), 2);
        assertThat(errors(ats(docx), "ATS-06")).extracting(Finding::message)
                .anySatisfy(m -> assertThat(m).contains("table"))
                .anySatisfy(m -> assertThat(m).contains("Page 1"));
    }

    @Test
    void ats07FontsNotEmbeddedTooSmallOrOffTheList() {
        ExportedFile small = new ExportedFile(ExportedFile.Type.PDF, PDF.bytes(), PDF.extractedText(), PDF.pageCount(),
                new FileFacts(List.of(new FontUse("ComicSansMS", false)), 9.0, 9.0, 0, 0, 0, ""));
        List<Finding> findings = ats(small).category(Category.ATS).findings().stream().filter(f -> f.checkId().equals("ATS-07")).toList();
        assertThat(findings).extracting(Finding::severity).contains(Severity.ERROR, Severity.WARN);
        assertThat(findings).extracting(Finding::message).anySatisfy(m -> assertThat(m).contains("not embedded"))
                .anySatisfy(m -> assertThat(m).contains("allow-list"));
    }

    @Test
    void ats08OddGlyphs() {
        String odd = PDF.extractedText().replace("• Reduced", "➢ Reduced").replace("Built REST", " Built REST");
        assertThat(ats(withText(odd)).category(Category.ATS).findings()).filteredOn(f -> f.checkId().equals("ATS-08")).hasSize(2);
    }

    @Test
    void ats10ParserLosesFields() {
        String noCommas = PDF.extractedText()
                .replace("Software Engineering Intern, Example Fintech Pte Ltd, Singapore", "Software Engineering Intern at Example Fintech Pte Ltd")
                .replace("Mechanical Design Engineer, Acme Precision Engineering Pte Ltd,", "Mechanical Design Engineer at Acme Precision Engineering Pte Ltd")
                .replace("Volunteer Developer, Code for Example SG", "Volunteer Developer at Code for Example SG");
        assertThat(ats(withText(noCommas)).category(Category.ATS).findings()).anySatisfy(f -> {
            assertThat(f.checkId()).isEqualTo("ATS-10");
            assertThat(f.evidence()).contains("Employer (Software Engineering Intern)");
        });
        var parsed = new HeuristicResumeParser().parse(PDF.extractedText());
        assertThat(parsed.name()).isEqualTo("Jordan Tan");
        assertThat(parsed.experience()).extracting(ResumeParser.Item::org).contains("Example Fintech Pte Ltd", "Acme Precision Engineering Pte Ltd");
        assertThat(parsed.education()).extracting(ResumeParser.Item::org).contains("Example Institute of Systems Science");
    }

    @Test
    void sg04ImagesInThePdf() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            var image = LosslessFactory.createFromImage(doc, new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(image, 10, 10);
            }
            doc.save(out);
        }
        ExportedFile withPhoto = new ExportedFile(ExportedFile.Type.PDF, out.toByteArray(), PDF.extractedText(), 1, PDF.facts());
        assertThat(engine.run(context(withPhoto), EnumSet.of(Category.SG)).errors()).extracting(Finding::checkId).contains("SG-04");
        assertThat(engine.run(context(PDF), EnumSet.of(Category.SG)).errors()).extracting(Finding::checkId).doesNotContain("SG-04");
    }

    // ---------------------------------------------------------------- helpers

    private CheckReport ats(ExportedFile file) {
        return engine.run(context(file), EnumSet.of(Category.ATS));
    }

    private CheckContext context(ExportedFile file) {
        return CheckContexts.fromDocument(CheckMode.EXPORT, SAMPLE, DOC, thresholds.current(), 2, true)
                .pageCount(file.pageCount()).export(file).build();
    }

    private static ExportedFile withText(String text) {
        return new ExportedFile(ExportedFile.Type.PDF, PDF.bytes(), text, PDF.pageCount(), PDF.facts());
    }

    private static List<Finding> errors(CheckReport report, String id) {
        return report.category(Category.ATS).findings().stream().filter(f -> f.checkId().equals(id) && f.severity() == Severity.ERROR).toList();
    }
}
