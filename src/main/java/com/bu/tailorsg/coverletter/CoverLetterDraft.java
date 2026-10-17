package com.bu.tailorsg.coverletter;

import java.util.List;

/**
 * The writer model's cover letter (spec 5.4 output), plus nothing else: the contact line and date are
 * added by the renderer from the master (P2).
 *
 * @param wordCount as reported by the model; CL-02 recounts the body itself
 */
public record CoverLetterDraft(String recipientLine, String subject, List<Paragraph> paragraphs, String signOff,
                               int wordCount) {

    public CoverLetterDraft {
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
    }

    public record Paragraph(String text, List<String> sourceIds) {
        public Paragraph {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    /** The paragraphs only, separated by blank lines (what CL-02 counts and most CL rules read). */
    public String bodyText() {
        return String.join("\n\n", paragraphs.stream().map(Paragraph::text).toList());
    }

    /** Salutation, subject, body and sign-off: everything the model wrote. */
    public String fullText() {
        return String.join("\n\n", List.of(nz(recipientLine), nz(subject), bodyText(), nz(signOff)));
    }

    public CoverLetterDraft withParagraphs(List<Paragraph> newParagraphs) {
        return new CoverLetterDraft(recipientLine, subject, newParagraphs, signOff, wordCount);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
