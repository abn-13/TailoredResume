package com.bu.tailorsg.checks.kw;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The keyword dictionary (spec 6C): {@code skills/tech_skills.csv}, plus {@code skills/sfw_ict.csv} if present
 * (Singapore Skills Framework for ICT technical skill titles, one per line or in the same CSV format).
 *
 * <p>Each entry has a canonical name, a category, aliases ("Postgres" for "PostgreSQL") and an optional
 * case-sensitive flag for names that are also ordinary words ("React", "Swift", "Go").
 */
@Component
public class SkillDictionary {

    private static final Logger log = LoggerFactory.getLogger(SkillDictionary.class);

    /** @param variants canonical name first, then aliases */
    public record Entry(String canonical, String category, List<String> variants, boolean caseSensitive) {}

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byVariant = new HashMap<>();

    public SkillDictionary(ResourceLoader loader) {
        load(loader.getResource("classpath:skills/tech_skills.csv"), true);
        load(loader.getResource("classpath:skills/sfw_ict.csv"), false);
        log.info("Skill dictionary: {} entries, {} variants", entries.size(), byVariant.size());
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** The entry a term belongs to, by canonical name or alias (case-insensitive). */
    public Optional<Entry> lookup(String term) {
        return term == null ? Optional.empty() : Optional.ofNullable(byVariant.get(term.strip().toLowerCase(Locale.ROOT)));
    }

    private void load(Resource resource, boolean required) {
        if (!resource.exists()) {
            if (required) {
                throw new IllegalStateException("Missing " + resource.getDescription());
            }
            return;
        }
        try {
            for (String line : resource.getContentAsString(StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("canonical,")) {
                    continue;
                }
                String[] cols = line.split(",", -1);
                String canonical = cols[0].strip();
                String category = cols.length > 1 ? cols[1].strip() : "Skill";
                List<String> variants = new ArrayList<>(List.of(canonical));
                if (cols.length > 2 && !cols[2].isBlank()) {
                    for (String alias : cols[2].split("\\|")) {
                        if (!alias.isBlank()) {
                            variants.add(alias.strip());
                        }
                    }
                }
                boolean cs = cols.length > 3 && cols[3].strip().equalsIgnoreCase("cs");
                Entry entry = new Entry(canonical, category, List.copyOf(variants), cs);
                entries.add(entry);
                for (String v : variants) {
                    byVariant.putIfAbsent(v.toLowerCase(Locale.ROOT), entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
