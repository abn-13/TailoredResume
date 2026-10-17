package com.bu.tailorsg.llm;

/**
 * Which configured model a prompt runs on. Prompts declare their role in front matter;
 * the actual model ID comes from {@code tailorsg.models.*} so it can change without code edits.
 */
public enum ModelRole {
    /** Quality-sensitive generation: tailoring, cover letters (tailorsg.models.writer). */
    WRITER,
    /** Cheap, fast structuring: JD extraction, free-text parsing (tailorsg.models.extractor). */
    EXTRACTOR
}
