package com.bu.tailorsg.checks;

/** The scorecard panels (spec 6). Order here is the order shown in the UI. */
public enum Category {
    TRUTH("No fabrication"),
    SG("Singapore norms"),
    ATS("ATS parseability"),
    KW("Keyword match"),
    LINT("Bullet quality"),
    CL("Cover letter");

    private final String label;

    Category(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
