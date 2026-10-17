package com.bu.tailorsg.tailoring;

import java.util.ArrayList;
import java.util.List;

/**
 * The writer model's tailoring output (spec 2.4), validated against schema/llm/tailored-resume.schema.json.
 *
 * <p>It contains only choices and wording: which highlights, in what words, in what order. Employer names,
 * titles, dates, education and contact details are not here at all; the renderer takes them from the master
 * by {@code workId} / {@code projectId} (P2). Every bullet cites master highlight IDs (P3).
 *
 * @param summary          text "" means no summary
 * @param gapsNotAddressed JD terms with no evidence in the master (the model must not mention them)
 */
public record TailoredResume(
        String targetTitleLine,
        Summary summary,
        List<String> sectionOrder,
        List<ExperienceItem> experience,
        List<ProjectItem> projects,
        List<SkillsLine> skillsLines,
        List<Omitted> omitted,
        List<String> gapsNotAddressed) {

    public TailoredResume {
        summary = summary == null ? new Summary("", List.of()) : summary;
        sectionOrder = orEmpty(sectionOrder);
        experience = orEmpty(experience);
        projects = orEmpty(projects);
        skillsLines = orEmpty(skillsLines);
        omitted = orEmpty(omitted);
        gapsNotAddressed = orEmpty(gapsNotAddressed);
    }

    public record Summary(String text, List<String> sourceIds) {
        public Summary {
            text = text == null ? "" : text;
            sourceIds = orEmpty(sourceIds);
        }

        public boolean isPresent() {
            return !text.isBlank();
        }
    }

    public record Bullet(String text, List<String> sourceIds) {
        public Bullet {
            sourceIds = orEmpty(sourceIds);
        }
    }

    /** @param workId a work-N or vol-N ID from the master */
    public record ExperienceItem(String workId, List<Bullet> bullets) {
        public ExperienceItem {
            bullets = orEmpty(bullets);
        }
    }

    public record ProjectItem(String projectId, List<Bullet> bullets) {
        public ProjectItem {
            bullets = orEmpty(bullets);
        }
    }

    /** @param items each names a master skill; the renderer prints the master's name for skillId (P2) */
    public record SkillsLine(String category, List<SkillItem> items) {
        public SkillsLine {
            items = orEmpty(items);
        }
    }

    public record SkillItem(String name, String skillId) {}

    public record Omitted(String id, String reason) {}

    /** Copy with one bullet's text replaced (manual edit). Section is "experience" or "projects". */
    public TailoredResume withBulletText(String section, String itemId, int index, String text) {
        List<ExperienceItem> exp = new ArrayList<>(experience);
        List<ProjectItem> proj = new ArrayList<>(projects);
        if (section.equals("experience")) {
            for (int i = 0; i < exp.size(); i++) {
                if (exp.get(i).workId().equals(itemId)) {
                    exp.set(i, new ExperienceItem(itemId, replace(exp.get(i).bullets(), index, text)));
                }
            }
        } else {
            for (int i = 0; i < proj.size(); i++) {
                if (proj.get(i).projectId().equals(itemId)) {
                    proj.set(i, new ProjectItem(itemId, replace(proj.get(i).bullets(), index, text)));
                }
            }
        }
        return new TailoredResume(targetTitleLine, summary, sectionOrder, exp, proj, skillsLines, omitted, gapsNotAddressed);
    }

    /** Copy with a bullet appended to an entry (e.g. adding an evidenced keyword from the master). */
    public TailoredResume withAddedBullet(String section, String itemId, Bullet bullet) {
        List<ExperienceItem> exp = new ArrayList<>(experience);
        List<ProjectItem> proj = new ArrayList<>(projects);
        boolean added = false;
        if (section.equals("experience")) {
            for (int i = 0; i < exp.size(); i++) {
                if (exp.get(i).workId().equals(itemId)) {
                    List<Bullet> b = new ArrayList<>(exp.get(i).bullets());
                    b.add(bullet);
                    exp.set(i, new ExperienceItem(itemId, b));
                    added = true;
                }
            }
            if (!added) {
                exp.add(new ExperienceItem(itemId, List.of(bullet)));
            }
        } else {
            for (int i = 0; i < proj.size(); i++) {
                if (proj.get(i).projectId().equals(itemId)) {
                    List<Bullet> b = new ArrayList<>(proj.get(i).bullets());
                    b.add(bullet);
                    proj.set(i, new ProjectItem(itemId, b));
                    added = true;
                }
            }
            if (!added) {
                proj.add(new ProjectItem(itemId, List.of(bullet)));
            }
        }
        return new TailoredResume(targetTitleLine, summary, sectionOrder, exp, proj, skillsLines, omitted, gapsNotAddressed);
    }

    /** Copy with the summary text replaced, keeping its citations (manual edit). */
    public TailoredResume withSummaryText(String text) {
        return new TailoredResume(targetTitleLine, new Summary(text, summary.sourceIds()), sectionOrder, experience, projects,
                skillsLines, omitted, gapsNotAddressed);
    }

    /** Copy without one bullet. */
    public TailoredResume withoutBullet(String section, String itemId, int index) {
        return withBulletText(section, itemId, index, null);
    }

    private static List<Bullet> replace(List<Bullet> bullets, int index, String text) {
        List<Bullet> copy = new ArrayList<>(bullets);
        if (index >= 0 && index < copy.size()) {
            if (text == null) {
                copy.remove(index);
            } else {
                copy.set(index, new Bullet(text, copy.get(index).sourceIds()));
            }
        }
        return copy;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
