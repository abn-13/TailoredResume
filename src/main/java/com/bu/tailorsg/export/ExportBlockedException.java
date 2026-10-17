package com.bu.tailorsg.export;

/** P5: an export with ERROR findings and no typed override reason. The check result is still stored. */
public class ExportBlockedException extends RuntimeException {

    private final ExportService.ExportCheck check;

    public ExportBlockedException(ExportService.ExportCheck check) {
        super("Export blocked: " + check.report().errors().size() + " ERROR finding(s). Fix them, or give a reason to export anyway.");
        this.check = check;
    }

    public ExportService.ExportCheck check() {
        return check;
    }
}
