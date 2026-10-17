package com.bu.tailorsg.export;

import java.util.List;

/**
 * A rendered PDF or DOCX, with what a parser can read back from it (for the ATS round-trip checks).
 *
 * @param extractedText PDFBox / POI text of the file
 * @param pageCount     PDF: real page count. DOCX: taken from the PDF of the same content (Word lays
 *                      pages out only when opened)
 */
public record ExportedFile(Type type, byte[] bytes, String extractedText, int pageCount, FileFacts facts) {

    public enum Type { PDF, DOCX }

    /**
     * Facts about the file itself that the ATS and SG-04 rules look at.
     *
     * @param bodyFontPt the font size used by most characters
     * @param textBoxes  DOCX text boxes, shapes or drawings holding text (always 0 for a PDF)
     */
    public record FileFacts(List<FontUse> fonts, double bodyFontPt, double minFontPt, int images, int tables, int textBoxes,
                            String headerFooterText) {
        public FileFacts {
            fonts = fonts == null ? List.of() : List.copyOf(fonts);
            headerFooterText = headerFooterText == null ? "" : headerFooterText;
        }
    }

    /** @param name without a subset prefix ("ABCDEF+LiberationSans" becomes "LiberationSans") */
    public record FontUse(String name, boolean embedded) {}
}
