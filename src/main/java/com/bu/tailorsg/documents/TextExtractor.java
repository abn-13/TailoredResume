package com.bu.tailorsg.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.text.Normalizer;

/**
 * Plain text from PDF and DOCX files: uploaded resumes now, and the round-trip ATS checks in Phase 6
 * (spec 0: "PDFBox PDFTextStripper with sortByPosition=true and POI XWPFWordExtractor").
 *
 * <p>The file type is decided from the first bytes (the "magic number"), never from the file name or
 * the browser's content type, which are easy to get wrong.
 */
@Component
public class TextExtractor {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    /** Less text than this means a scanned or image-only file (no OCR in v1). */
    static final int MIN_TEXT_CHARS = 50;

    public enum DocumentType { PDF, DOCX }

    public record Extracted(DocumentType type, String text, int pages) {}

    public Extracted extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new UnreadableDocumentException("The file is empty.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new UnreadableDocumentException("The file is larger than 5 MB. A resume should be much smaller.");
        }
        Extracted extracted = switch (detect(bytes)) {
            case PDF -> pdf(bytes);
            case DOCX -> docx(bytes);
        };
        String text = normalise(extracted.text());
        if (text.replaceAll("\\s", "").length() < MIN_TEXT_CHARS) {
            throw new UnreadableDocumentException("No readable text found. This looks like a scanned or image-only "
                    + "file, and TailorSG cannot read those (no OCR). Export your resume as a text-based PDF or DOCX.");
        }
        return new Extracted(extracted.type(), text, extracted.pages());
    }

    static DocumentType detect(byte[] bytes) {
        if (startsWith(bytes, "%PDF-".getBytes())) {
            return DocumentType.PDF;
        }
        if (startsWith(bytes, new byte[] {'P', 'K', 3, 4})) {
            return DocumentType.DOCX; // a ZIP; POI checks that it really is a Word document
        }
        if (startsWith(bytes, new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0})) {
            throw new UnreadableDocumentException("This is an old .doc file. Save it as .docx or PDF and upload that.");
        }
        throw new UnreadableDocumentException("Only PDF and DOCX files are supported.");
    }

    private static Extracted pdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            // Read in visual order (top to bottom, left to right) rather than the order text was drawn,
            // which is what an ATS does too.
            stripper.setSortByPosition(true);
            return new Extracted(DocumentType.PDF, stripper.getText(document), document.getNumberOfPages());
        } catch (InvalidPasswordException e) {
            throw new UnreadableDocumentException("This PDF is password-protected. Remove the password and try again.");
        } catch (IOException e) {
            throw new UnreadableDocumentException("Could not read this PDF: " + e.getMessage());
        }
    }

    private static Extracted docx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            int pages = document.getProperties().getExtendedProperties().getPages();
            return new Extracted(DocumentType.DOCX, extractor.getText(), pages);
        } catch (OLE2NotOfficeXmlFileException e) {
            throw new UnreadableDocumentException("This is an old .doc file. Save it as .docx or PDF and upload that.");
        } catch (IOException | POIXMLException | IllegalArgumentException e) {
            throw new UnreadableDocumentException("Could not read this Word file. Is it a .docx document?");
        }
    }

    /**
     * NFKC turns ligatures ("ﬁ") and non-breaking spaces into plain characters; soft hyphens are dropped;
     * runs of blank lines are collapsed. Words and line breaks are otherwise kept as extracted.
     */
    static String normalise(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("­", "")
                .replace("\r\n", "\n")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
