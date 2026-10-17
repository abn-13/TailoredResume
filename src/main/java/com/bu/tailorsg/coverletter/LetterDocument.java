package com.bu.tailorsg.coverletter;

import java.util.ArrayList;
import java.util.List;

/**
 * A cover letter laid out for reading and export: the parts the model wrote (salutation, subject, body,
 * sign-off) between parts that come straight from the master and the job (name, contact line, date,
 * recipient block), so the model never types them (P2).
 */
public record LetterDocument(String name, String contactLine, String date, List<String> recipientBlock, String subject,
                             String salutation, List<String> paragraphs, String signOff) {

    public LetterDocument {
        recipientBlock = List.copyOf(recipientBlock);
        paragraphs = List.copyOf(paragraphs);
    }

    /** Blocks in reading order (DOCX paragraphs, plain text): lines within a block are joined by newlines. */
    public List<String> blocks() {
        List<String> blocks = new ArrayList<>();
        blocks.add(name + "\n" + contactLine);
        blocks.add(date);
        blocks.add(String.join("\n", recipientBlock));
        blocks.add(subject);
        blocks.add(salutation);
        blocks.addAll(paragraphs);
        blocks.add(signOff + "\n" + name);
        return blocks;
    }

    public String plainText() {
        return String.join("\n\n", blocks());
    }
}
