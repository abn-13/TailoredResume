package com.bu.tailorsg.checks;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One deterministic rule (spec 6). Implementations are Spring beans, picked up by {@link CheckEngine}.
 * Each implementation's Javadoc cites the basis for the rule.
 */
public interface Check {

    /** e.g. "SG-01" */
    String id();

    Category category();

    /** The modes this rule applies to. */
    default Set<CheckMode> modes() {
        return EnumSet.of(CheckMode.MASTER, CheckMode.RESUME, CheckMode.EXPORT);
    }

    List<Finding> run(CheckContext ctx);
}
