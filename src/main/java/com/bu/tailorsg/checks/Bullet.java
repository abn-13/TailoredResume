package com.bu.tailorsg.checks;

import java.util.List;

/**
 * One bullet as the LINT, KW and TRUTH rules see it, whether it comes from the master resume or from a
 * tailored resume.
 *
 * @param anchor      HTML id of the bullet in previews, e.g. "b-work-1-0", used by "jump to"
 * @param label       human-readable location, e.g. "work-1 bullet 1"
 * @param itemId      the work/project/education entry it belongs to
 * @param currentRole true when the entry has no end date (present tense allowed)
 * @param sourceIds   master highlight IDs it is derived from (for a master bullet: its own ID)
 */
public record Bullet(String anchor, String label, Kind kind, String itemId, int index, boolean currentRole,
                     String text, List<String> sourceIds) {

    public enum Kind { EXPERIENCE, PROJECT, OTHER }

    public Bullet {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }
}
