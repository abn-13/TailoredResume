package com.bu.tailorsg.checks.cl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashFixerTest {

    @Test
    void replacesDashesWithoutChangingWords() {
        assertThat(DashFixer.fix("The change was small — one cache in front of one query — but it mattered."))
                .isEqualTo("The change was small, one cache in front of one query, but it mattered.");
        assertThat(DashFixer.fix("It took 2–3 weeks.")).isEqualTo("It took 2 to 3 weeks.");
        assertThat(DashFixer.fix("Java—my strongest language.")).isEqualTo("Java, my strongest language.");
        assertThat(DashFixer.fix("Fast —.")).isEqualTo("Fast.");
        assertThat(DashFixer.fix("No dashes here.")).isEqualTo("No dashes here.");
        assertThat(DashFixer.hasDash("a – b")).isTrue();
        assertThat(DashFixer.hasDash("well-tested")).isFalse();
    }
}
