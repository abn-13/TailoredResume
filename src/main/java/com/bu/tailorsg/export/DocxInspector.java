package com.bu.tailorsg.export;

import com.bu.tailorsg.export.ExportedFile.FileFacts;
import com.bu.tailorsg.export.ExportedFile.FontUse;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads a DOCX back like a parser would and collects ATS-06 / ATS-07 facts: tables, text
 * boxes and shapes, pictures, header/footer text, fonts and sizes.
 *
 * @see PdfInspector
 */
public final class DocxInspector {

    private DocxInspector() {}

    /** @param pageCount from the PDF of the same content (POI cannot lay out pages) */
    public static ExportedFile inspect(byte[] bytes, int pageCount) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = text(doc);
            String xml = doc.getDocument().xmlText();
            int textBoxes = count(xml, "txbxContent") + count(xml, "<v:shape") + count(xml, "<wps:wsp");
            int tables = doc.getTables().size();
            int images = doc.getAllPictures().size();
            StringBuilder headerFooter = new StringBuilder();
            List<XWPFHeaderFooter> hf = new ArrayList<>(doc.getHeaderList());
            hf.addAll(doc.getFooterList());
            hf.forEach(h -> headerFooter.append(h.getText()));

            Map<String, FontUse> fonts = new LinkedHashMap<>();
            TreeMap<Double, Integer> sizes = new TreeMap<>();
            for (XWPFParagraph p : doc.getParagraphs()) {
                for (XWPFRun r : p.getRuns()) {
                    String t = r.text();
                    if (t == null || t.isBlank()) {
                        continue;
                    }
                    String family = r.getFontFamily() == null ? "(default)" : r.getFontFamily();
                    fonts.putIfAbsent(family, new FontUse(family, true)); // Word documents name fonts; embedding does not apply
                    Double pt = r.getFontSizeAsDouble();
                    sizes.merge(pt == null ? 11.0 : pt, t.strip().length(), Integer::sum);
                }
            }
            double body = sizes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0.0);
            double min = sizes.isEmpty() ? 0 : sizes.firstKey();
            FileFacts facts = new FileFacts(new ArrayList<>(fonts.values()), body, min, images, tables, textBoxes,
                    headerFooter.toString().strip());
            return new ExportedFile(ExportedFile.Type.DOCX, bytes, text, pageCount, facts);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the DOCX back", e);
        }
    }

    /**
     * Body text in reading order, with "• " before list paragraphs and table cells included. POI's
     * XWPFWordExtractor drops list bullets, so every bullet would look like a plain line; Apache Tika (which
     * many applicant tracking systems use) keeps them, and so do we.
     */
    static String text(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFParagraph p) {
                sb.append(p.getNumID() != null ? "\u2022 " : "").append(p.getText()).append('\n');
            } else if (element instanceof XWPFTable t) {
                t.getRows().forEach(r -> r.getTableCells().forEach(c -> sb.append(c.getText()).append('\n')));
            }
        }
        return sb.toString();
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
