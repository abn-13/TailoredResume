package com.bu.tailorsg.updates;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class UpdateNotFoundException extends RuntimeException {

    public UpdateNotFoundException(long id) {
        super("Update #" + id + " does not exist.");
    }
}
