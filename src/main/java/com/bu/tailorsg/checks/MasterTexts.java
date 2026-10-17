package com.bu.tailorsg.checks;

import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResume.Highlight;

import java.util.ArrayList;
import java.util.List;

/** All text in a master resume, joined, for "does this appear anywhere in the master?" (KW, TRUTH-04). */
public final class MasterTexts {

    private MasterTexts() {}

    public static String all(MasterResume m) {
        if (m == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        var b = m.basics();
        if (b != null) {
            add(parts, b.name(), b.label());
            if (b.workAuthorization() != null) {
                add(parts, b.workAuthorization().displayText());
            }
            b.summaryVariants().forEach(s -> add(parts, s.text()));
        }
        m.work().forEach(w -> { add(parts, w.name(), w.position(), w.location(), w.summary()); highlights(parts, w.highlights()); });
        m.volunteer().forEach(w -> { add(parts, w.name(), w.position(), w.location(), w.summary()); highlights(parts, w.highlights()); });
        m.projects().forEach(p -> { add(parts, p.name(), p.role(), p.description()); p.keywords().forEach(parts::add); highlights(parts, p.highlights()); });
        m.education().forEach(e -> { add(parts, e.institution(), e.area(), e.studyType()); e.courses().forEach(parts::add); highlights(parts, e.highlights()); });
        m.skills().forEach(s -> add(parts, s.name()));
        m.certificates().forEach(c -> add(parts, c.name(), c.issuer()));
        m.awards().forEach(a -> add(parts, a.title(), a.awarder(), a.summary()));
        m.extras().forEach(x -> add(parts, x.text()));
        return String.join("\n", parts);
    }

    /** One highlight's text, skills and metric values: what a bullet citing it may draw on. */
    public static String of(Highlight h) {
        List<String> parts = new ArrayList<>();
        add(parts, h.text());
        h.skills().forEach(parts::add);
        h.metrics().forEach(x -> add(parts, x.value() + (x.unit() == null ? "" : " " + x.unit()), x.label()));
        return String.join("\n", parts);
    }

    private static void highlights(List<String> parts, List<Highlight> highlights) {
        highlights.forEach(h -> parts.add(of(h)));
    }

    private static void add(List<String> parts, String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                parts.add(v);
            }
        }
    }
}
