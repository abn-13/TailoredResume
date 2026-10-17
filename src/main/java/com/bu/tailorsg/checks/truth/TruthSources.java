package com.bu.tailorsg.checks.truth;

import com.bu.tailorsg.checks.Bullet;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.MasterTexts;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResume.Highlight;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What each generated item may draw on (P3): its cited master highlights, and for a cover letter also the
 * job's stated company facts and the user's own notes (spec 6E, last paragraph).
 */
final class TruthSources {

    /**
     * One piece of generated text and what it cites.
     *
     * @param itemId the work/project entry it sits under, or null (summary, cover letter paragraph)
     */
    record Item(String anchor, String label, String text, List<String> sourceIds, String itemId) {}

    final Map<String, Highlight> highlights = new HashMap<>();
    final Map<String, String> ownerOf = new HashMap<>();
    final Map<String, String> summaries = new HashMap<>();
    final String extraAllowed;
    final List<Item> items = new ArrayList<>();

    TruthSources(CheckContext ctx) {
        MasterResume m = ctx.master();
        m.work().forEach(w -> w.highlights().forEach(h -> put(h, w.id())));
        m.volunteer().forEach(w -> w.highlights().forEach(h -> put(h, w.id())));
        m.projects().forEach(p -> p.highlights().forEach(h -> put(h, p.id())));
        m.education().forEach(e -> e.highlights().forEach(h -> put(h, e.id())));
        m.basics().summaryVariants().forEach(s -> summaries.put(s.id(), s.text()));

        if (ctx.mode() == CheckMode.COVER_LETTER && ctx.coverLetter() != null) {
            var paragraphs = ctx.coverLetter().paragraphs();
            for (int i = 0; i < paragraphs.size(); i++) {
                items.add(new Item("p-" + i, "paragraph " + (i + 1), paragraphs.get(i).text(), paragraphs.get(i).sourceIds(), null));
            }
            List<String> extra = new ArrayList<>();
            if (ctx.job() != null) {
                extra.addAll(ctx.job().companyFacts());
                add(extra, ctx.job().title());
                add(extra, ctx.job().company());
                add(extra, ctx.job().location());
            }
            extra.addAll(ctx.userNotes().values());
            extraAllowed = String.join("\n", extra);
        } else {
            for (Bullet b : ctx.bullets()) {
                items.add(new Item(b.anchor(), b.label(), b.text(), b.sourceIds(), b.itemId()));
            }
            if (ctx.tailored() != null && ctx.tailored().summary().isPresent()) {
                var s = ctx.tailored().summary();
                items.add(new Item("summary", "summary", s.text(), s.sourceIds(), null));
            }
            extraAllowed = "";
        }
    }

    /** The text an item may use: cited highlights (text, skills, metrics) and cited summary variants. */
    String sourceText(Item item) {
        List<String> parts = new ArrayList<>();
        for (String id : item.sourceIds()) {
            Highlight h = highlights.get(id);
            if (h != null) {
                parts.add(MasterTexts.of(h));
            } else if (summaries.containsKey(id)) {
                parts.add(summaries.get(id));
            }
        }
        if (!extraAllowed.isBlank()) {
            parts.add(extraAllowed);
        }
        return String.join("\n", parts);
    }

    boolean exists(String id) {
        return highlights.containsKey(id) || summaries.containsKey(id);
    }

    private void put(Highlight h, String owner) {
        highlights.put(h.id(), h);
        ownerOf.put(h.id(), owner);
    }

    private static void add(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }
}
