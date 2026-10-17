package com.bu.tailorsg.master;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A requested master resume version does not exist (or there is no master resume yet).
 * {@code @ResponseStatus} makes pages answer 404 instead of 500; the API maps it to a problem detail.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class VersionNotFoundException extends RuntimeException {

    public VersionNotFoundException(String message) {
        super(message);
    }

    public static VersionNotFoundException version(int versionNo) {
        return new VersionNotFoundException("Master resume version " + versionNo + " does not exist.");
    }

    public static VersionNotFoundException noMaster() {
        return new VersionNotFoundException("There is no master resume yet. Save one, or load the sample.");
    }
}
