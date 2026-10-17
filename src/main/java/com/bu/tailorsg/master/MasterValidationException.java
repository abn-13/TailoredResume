package com.bu.tailorsg.master;

import java.util.List;

/** A master resume failed validation; nothing was saved. Carries every error found, not just the first. */
public class MasterValidationException extends RuntimeException {

    private final List<ValidationError> errors;

    public MasterValidationException(List<ValidationError> errors) {
        super(errors.size() + " validation error(s); first: " + errors.getFirst().displayPath()
                + " " + errors.getFirst().message());
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
