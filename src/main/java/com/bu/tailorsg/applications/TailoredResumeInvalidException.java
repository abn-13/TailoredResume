package com.bu.tailorsg.applications;

import com.bu.tailorsg.llm.JsonSchemaService.SchemaViolation;

import java.util.List;

/** A manual edit to the tailored resume (PUT /api/applications/{id}/resume) failed the schema. */
public class TailoredResumeInvalidException extends RuntimeException {

    private final List<SchemaViolation> errors;

    public TailoredResumeInvalidException(List<SchemaViolation> errors) {
        super(errors.size() + " problem(s) in the tailored resume; nothing was saved.");
        this.errors = List.copyOf(errors);
    }

    public List<SchemaViolation> errors() {
        return errors;
    }
}
