package com.bu.tailorsg.export;

import com.bu.tailorsg.export.ExportedFile.FileFacts;
import com.bu.tailorsg.export.ExportedFile.FontUse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a PDF back the way a parser would (PDFBox text in content order), plus the facts the ATS rules need:
 * fonts and whether they are embedded, font sizes by character count, images.
 */
public final class PdfInspector {

    private PdfInspector() {}

    public static ExportedFile inspect(byte[] pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            SizeCollector stripper = new SizeCollector();
            String text = stripper.getText(doc);
            Map<String, FontUse> fonts = new LinkedHashMap<>();
            int images = 0;
            for (PDPage page : doc.getPages()) {
                PDResources res = page.getResources();
                for (COSName name : res.getFontNames()) {
                    PDFont font = res.getFont(name);
                    String base = font.getName() == null ? name.getName() : font.getName().replaceFirst("^[A-Z]{6}\\+", "");
                    fonts.merge(base, new FontUse(base, font.isEmbedded()), (a, b) -> new FontUse(a.name(), a.embedded() && b.embedded()));
                }
                for (COSName name : res.getXObjectNames()) {
                    if (res.getXObject(name) instanceof PDImageXObject) {
                        images++;
                    }
                }
            }
            FileFacts facts = new FileFacts(new ArrayList<>(fonts.values()), stripper.bodySize(), stripper.minSize(), images, 0, 0, "");
            return new ExportedFile(ExportedFile.Type.PDF, pdf, text, doc.getNumberOfPages(), facts);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the PDF back", e);
        }
    }

    /** Counts characters per font size (rounded to the nearest 0.5pt) while extracting text. */
    static final class SizeCollector extends PDFTextStripper {
        private final TreeMap<Double, Integer> chars = new TreeMap<>();

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            for (TextPosition p : positions) {
                if (!p.getUnicode().isBlank()) {
                    // The text matrix scale is the size on the page in points. getFontSizeInPt() truncates to
                    // whole points (11pt read as 10), which would make ATS-07 fail every file.
                    chars.merge(Math.round(p.getYScale() * 2) / 2.0, 1, Integer::sum);
                }
            }
            super.writeString(text, positions);
        }

        double bodySize() {
            return chars.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0.0);
        }

        double minSize() {
            return chars.isEmpty() ? 0 : chars.firstKey();
        }
    }
}
