package com.bu.tailorsg.updates.form;

import java.util.List;

/** The skill categories allowed by the master resume schema, for form selects and validation. */
public final class SkillCategories {

    public static final List<String> ALL =
            List.of("Languages", "Frameworks", "Cloud/DevOps", "Databases", "Tools", "Domain", "Soft");

    /** Must stay a compile-time constant for use in annotations. */
    static final String REGEX = "Languages|Frameworks|Cloud/DevOps|Databases|Tools|Domain|Soft";

    private SkillCategories() {}
}
