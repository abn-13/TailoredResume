package com.bu.tailorsg.export;

import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeRenderer;
import com.bu.tailorsg.support.TestResources;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The PDF and DOCX renderers produce what the template promises, as read back by PDFBox and POI. */
class RenderAndInspectTest {

    static final MasterResume SAMPLE = JsonMapper.builder().build().readValue(TestResources.text("samples/master-sample.json"), MasterResume.class);
    static final ResumeDocument DOC = new ResumeRenderer().renderMaster(SAMPLE);

    @Test
    void pdfEmbedsLiberationSansAt11ptAndKeepsDatesWithTheirJobs() {
        ExportedFile pdf = PdfInspector.inspect(new PdfRenderer().render(ResumeHtml.of(DOC, "Jordan Tan - Resume")));

        assertThat(pdf.facts().fonts()).isNotEmpty().allSatisfy(f -> {
            assertThat(f.name()).startsWith("LiberationSans");
            assertThat(f.embedded()).isTrue();
        });
        assertThat(pdf.facts().bodyFontPt()).isEqualTo(11.0);
        assertThat(pdf.facts().minFontPt()).isEqualTo(10.5);
        assertThat(pdf.facts().images()).isZero();
        List<String> lines = pdf.extractedText().lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        assertThat(lines.get(0)).isEqualTo("Jordan Tan");
        // Regression: with float:right the dates were all extracted at the top of the page.
        assertThat(lines).contains("Software Engineering Intern, Example Fintech Pte Ltd, Singapore May 2026 - Present");
        assertThat(pdf.extractedText()).contains("• Reduced p95 latency").doesNotContain("\u00a0");
        assertThat(pdf.pageCount()).isEqualTo(2); // the whole master; a tailored resume targets 1
    }

    @Test
    void docxUsesRealNumberingTabsAndNoTables() throws Exception {
        byte[] bytes = new DocxRenderer().render(DOC, "Jordan Tan - Resume");
        ExportedFile docx = DocxInspector.inspect(bytes, 2);

        assertThat(docx.facts().tables()).isZero();
        assertThat(docx.facts().textBoxes()).isZero();
        assertThat(docx.facts().headerFooterText()).isEmpty();
        assertThat(docx.facts().fonts()).extracting(ExportedFile.FontUse::name).containsExactly("Arial");
        assertThat(docx.facts().bodyFontPt()).isEqualTo(11.0);
        assertThat(docx.extractedText()).contains("Software Engineering Intern, Example Fintech Pte Ltd, Singapore\tMay 2026 - Present")
                .contains("• Reduced p95 latency"); // Word numbering, read back as a bullet (as Apache Tika does)
        try (var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(bytes))) {
            assertThat(doc.getParagraphs()).anySatisfy(p -> assertThat(p.getNumID()).isNotNull());
        }
    }
}
