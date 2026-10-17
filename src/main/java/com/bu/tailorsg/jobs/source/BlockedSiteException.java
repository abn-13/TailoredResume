package com.bu.tailorsg.jobs.source;

/**
 * The site cannot or should not be fetched automatically (LinkedIn, 401/403/429, a login wall, robots.txt).
 * Spec 3: never try to bypass it; ask for pasted text or screenshots instead.
 */
public class BlockedSiteException extends RuntimeException {

    public static final String ADVICE = "This site blocks automated fetching. Paste the JD text or upload screenshots.";

    private final String reason;

    public BlockedSiteException(String reason) {
        super(ADVICE + " (" + reason + ")");
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
