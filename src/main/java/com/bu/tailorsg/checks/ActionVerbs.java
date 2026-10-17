package com.bu.tailorsg.checks;

import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The action-verb list ({@code checks/action_verbs.txt}, "Past|Present" per line) plus common irregular
 * past forms, for LINT-01 (strong first verb) and LINT-05 (tense). Loaded once; immutable.
 */
public final class ActionVerbs {

    /** Irregular past forms beyond the action-verb file, so "Ran", "Sent", "Made" count as past tense. */
    static final Set<String> IRREGULAR_PAST = Set.of(
            "built", "cut", "drove", "grew", "led", "ran", "rebuilt", "rewrote", "taught", "wrote", "won", "oversaw",
            "troubleshot", "forecast", "set", "put", "made", "took", "gave", "brought", "bought", "sold", "spent", "sent",
            "thought", "held", "kept", "met", "paid", "read", "spoke", "stood", "understood", "began", "became", "chose",
            "drew", "fought", "flew", "got", "hit", "knew", "laid", "left", "lent", "let", "lost", "meant", "rose", "saw",
            "shot", "shut", "split", "spread", "struck", "swept", "told", "threw", "withdrew", "sped", "found", "upheld");

    private static final ActionVerbs INSTANCE = load();

    private final Set<String> past;
    private final Set<String> present;

    private ActionVerbs(Set<String> past, Set<String> present) {
        this.past = past;
        this.present = present;
    }

    public static ActionVerbs get() {
        return INSTANCE;
    }

    public boolean isActionVerb(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return past.contains(w) || present.contains(w);
    }

    /** Heuristic from spec LINT-05: ends in -ed, or is a known past form. */
    public boolean isPastTense(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.endsWith("ed") || past.contains(w) || IRREGULAR_PAST.contains(w);
    }

    public boolean isPresentForm(String word) {
        return present.contains(word.toLowerCase(Locale.ROOT));
    }

    private static ActionVerbs load() {
        try {
            String text = new DefaultResourceLoader().getResource("classpath:checks/action_verbs.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
            Set<String> past = new HashSet<>();
            Set<String> present = new HashSet<>();
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.strip().split("\\|");
                past.add(parts[0].toLowerCase(Locale.ROOT));
                if (parts.length > 1) {
                    present.add(parts[1].toLowerCase(Locale.ROOT));
                }
            }
            return new ActionVerbs(Set.copyOf(past), Set.copyOf(present));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read checks/action_verbs.txt", e);
        }
    }
}
