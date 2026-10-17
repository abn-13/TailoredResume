package com.bu.tailorsg.documents;

import com.bu.tailorsg.documents.TextExtractor.DocumentType;
import com.bu.tailorsg.documents.TextExtractor.Extracted;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    private static final List<String> LINES = List.of(
            "Alex Lim",
            "alex.lim@example.com | Singapore",
            "Software Engineer Intern, Example Logistics Pte Ltd",
            "Built a Spring Boot service that tracks 1,200 parcels a day");

    @Test
    void extractsPdfTextInReadingOrder() {
        Extracted extracted = extractor.extract(TestDocuments.pdf(LINES));

        assertThat(extracted.type()).isEqualTo(DocumentType.PDF);
        assertThat(extracted.pages()).isEqualTo(1);
        assertThat(extracted.text()).containsSubsequence(LINES.toArray(String[]::new));
    }

    @Test
    void extractsDocxText() {
        Extracted extracted = extractor.extract(TestDocuments.docx(LINES));

        assertThat(extracted.type()).isEqualTo(DocumentType.DOCX);
        assertThat(extracted.text()).containsSubsequence(LINES.toArray(String[]::new));
    }

    @Test
    void imageOnlyOrBlankPdfIsExplainedNotSilentlyEmpty() {
        assertThatThrownBy(() -> extractor.extract(TestDocuments.pdf(List.of())))
                .isInstanceOf(UnreadableDocumentException.class)
                .hasMessageContaining("scanned or image-only");
    }

    @Test
    void typeComesFromTheBytesNotTheName() {
        assertThatThrownBy(() -> extractor.extract("just some text pretending to be a resume".getBytes()))
                .hasMessageContaining("Only PDF and DOCX");
        byte[] oldWordFile = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0};
        assertThatThrownBy(() -> extractor.extract(oldWordFile)).hasMessageContaining("old .doc");
        assertThatThrownBy(() -> extractor.extract(new byte[0])).hasMessageContaining("empty");
        assertThatThrownBy(() -> extractor.extract("%PDF-1.7 broken".getBytes())).hasMessageContaining("Could not read this PDF");
    }

    @Test
    void normalisationFixesLigaturesSoftHyphensAndBlankRuns() {
        assertThat(TextExtractor.normalise("eﬃcient de­ploy\r\n\n\n\nnext"))
                .isEqualTo("efficient deploy\n\nnext");
    }
}
