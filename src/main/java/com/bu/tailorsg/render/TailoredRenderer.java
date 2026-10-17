package com.bu.tailorsg.render;

import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResume.Experience;
import com.bu.tailorsg.master.MasterResume.Project;
import com.bu.tailorsg.master.MasterResume.Skill;
import com.bu.tailorsg.render.ResumeDocument.DocBullet;
import com.bu.tailorsg.render.ResumeDocument.Entry;
import com.bu.tailorsg.render.ResumeDocument.Kind;
import com.bu.tailorsg.render.ResumeDocument.Section;
import com.bu.tailorsg.render.ResumeDocument.SkillLine;
import com.bu.tailorsg.tailoring.TailoredResume;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a {@link TailoredResume} into a {@link ResumeDocument} (spec 4 template order, P2).
 *
 * <ul>
 *   <li>Employer, title, location and dates come from the master entry named by workId / projectId; the
 *       model's output has none of them. Education, certifications and awards are rendered straight from
 *       the master. Skill names come from the master skill named by skillId.</li>
 *   <li>Within each section, entries are always reverse-chronological (spec 5.3 rule 10), whatever order the
 *       model returned.</li>
 *   <li>Unknown IDs are skipped here and reported by TRUTH-01.</li>
 * </ul>
 */
@Component
public class TailoredRenderer {

    static final List<String> DEFAULT_ORDER = List.of("summary", "education", "experience", "projects", "skills");

    private final ResumeRenderer base;

    public TailoredRenderer(ResumeRenderer base) {
        this.base = base;
    }

    public ResumeDocument render(MasterResume m, TailoredResume t, boolean includeSummary, boolean showWorkAuth) {
        Set<String> order = new LinkedHashSet<>();
        t.sectionOrder().forEach(order::add);
        order.addAll(DEFAULT_ORDER);
        if (t.experience().stream().anyMatch(e -> e.workId().startsWith("vol-"))) {
            order.add("volunteering");
        }
        order.remove("summary");
        List<Section> sections = new ArrayList<>();
        if (includeSummary && t.summary().isPresent()) {
            sections.add(new Section(Kind.SUMMARY, "Summary", null, null, List.of(t.summary().text().strip())));
        }
        for (String name : order) {
            section(name, m, t).ifPresent(sections::add);
        }
        return new ResumeDocument(base.header(m.basics(), showWorkAuth), sections);
    }

    private Optional<Section> section(String name, MasterResume m, TailoredResume t) {
        return switch (name) {
            case "education" -> m.education().isEmpty() ? Optional.empty() : Optional.of(new Section(Kind.EDUCATION, "Education",
                    sortedEntries(m.education().stream().map(e -> base.education(e, List.of())).toList()), null, null));
            case "experience" -> experience(m, t, "work-", Kind.EXPERIENCE, "Experience");
            case "volunteering" -> experience(m, t, "vol-", Kind.VOLUNTEERING, "Volunteering");
            case "projects" -> projects(m, t);
            case "skills" -> skills(m, t);
            case "certifications" -> lines(Kind.CERTIFICATIONS, "Certifications", base.certificateLines(m));
            case "awards" -> lines(Kind.AWARDS, "Awards", base.awardLines(m));
            case "languages" -> m.languages().isEmpty() ? Optional.empty() : lines(Kind.LANGUAGES, "Languages",
                    List.of(String.join(", ", m.languages().stream()
                            .map(l -> l.fluency() == null ? l.language() : l.language() + " (" + l.fluency() + ")").toList())));
            default -> Optional.empty();
        };
    }

    private Optional<Section> experience(MasterResume m, TailoredResume t, String prefix, Kind kind, String heading) {
        List<Entry> entries = new ArrayList<>();
        for (TailoredResume.ExperienceItem item : t.experience()) {
            if (!item.workId().startsWith(prefix)) {
                continue;
            }
            List<Experience> pool = prefix.equals("work-") ? m.work() : m.volunteer();
            pool.stream().filter(w -> w.id().equals(item.workId())).findFirst()
                    .ifPresent(w -> entries.add(base.experience(w, bullets(item.workId(), item.bullets()))));
        }
        return entries.isEmpty() ? Optional.empty() : Optional.of(new Section(kind, heading, sortedEntries(entries), null, null));
    }

    private Optional<Section> projects(MasterResume m, TailoredResume t) {
        List<Entry> entries = new ArrayList<>();
        for (TailoredResume.ProjectItem item : t.projects()) {
            m.projects().stream().filter(p -> p.id().equals(item.projectId())).findFirst()
                    .ifPresent((Project p) -> entries.add(base.project(p, bullets(item.projectId(), item.bullets()))));
        }
        return entries.isEmpty() ? Optional.empty() : Optional.of(new Section(Kind.PROJECTS, "Projects", sortedEntries(entries), null, null));
    }

    private Optional<Section> skills(MasterResume m, TailoredResume t) {
        Map<String, Skill> byId = new LinkedHashMap<>();
        m.skills().forEach(s -> byId.put(s.id(), s));
        List<SkillLine> lines = new ArrayList<>();
        for (TailoredResume.SkillsLine line : t.skillsLines()) {
            List<String> names = line.items().stream().map(i -> byId.get(i.skillId())).filter(s -> s != null)
                    .map(Skill::name).distinct().toList(); // the master's spelling, never the model's (P2)
            if (!names.isEmpty()) {
                lines.add(new SkillLine(line.category().strip(), names));
            }
        }
        return lines.isEmpty() ? Optional.empty() : Optional.of(new Section(Kind.SKILLS, "Skills", null, lines, null));
    }

    private static Optional<Section> lines(Kind kind, String heading, List<String> lines) {
        return lines.isEmpty() ? Optional.empty() : Optional.of(new Section(kind, heading, null, null, lines));
    }

    private static List<DocBullet> bullets(String itemId, List<TailoredResume.Bullet> bullets) {
        List<DocBullet> out = new ArrayList<>();
        for (int i = 0; i < bullets.size(); i++) {
            out.add(new DocBullet("b-" + itemId + "-" + i, bullets.get(i).text().strip(), bullets.get(i).sourceIds()));
        }
        return out;
    }

    /** Current first, then by end date, then start date, newest first. */
    static List<Entry> sortedEntries(List<Entry> entries) {
        Comparator<Entry> byEnd = Comparator.comparing(e -> e.endDate() == null ? "9999-99" : e.endDate());
        Comparator<Entry> byStart = Comparator.comparing(e -> e.startDate() == null ? "0000-00" : e.startDate());
        return entries.stream().sorted(byEnd.thenComparing(byStart).reversed()).toList();
    }
}
