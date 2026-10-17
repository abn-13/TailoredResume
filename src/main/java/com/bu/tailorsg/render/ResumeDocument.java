package com.bu.tailorsg.render;

import java.util.ArrayList;
import java.util.List;

/**
 * A resume laid out as the reader sees it: header, then sections in order. Produced by
 * {@link ResumeRenderer} and consumed by every output: the HTML preview, the PDF, the DOCX, and the plain
 * text the checks read. One model for all of them is what makes the round-trip checks meaningful.
 */
public record ResumeDocument(Header header, List<Section> sections) {

    public ResumeDocument {
        sections = List.copyOf(sections);
    }

    /** @param workAuthLine e.g. "Work authorisation: Employment Pass required", or null when hidden */
    public record Header(String name, List<Contact> contacts, String workAuthLine) {
        public Header {
            contacts = List.copyOf(contacts);
        }
    }

    /** @param label visible text, e.g. "github.com/jordan" (spec 4: show the URL, not "click here") */
    public record Contact(String label, String href) {}

    public enum Kind { SUMMARY, EDUCATION, EXPERIENCE, PROJECTS, SKILLS, VOLUNTEERING, CERTIFICATIONS, AWARDS, LANGUAGES }

    /** Exactly one of entries / skillLines / lines is used, depending on the kind. */
    public record Section(Kind kind, String heading, List<Entry> entries, List<SkillLine> skillLines, List<String> lines) {
        public Section {
            entries = entries == null ? List.of() : List.copyOf(entries);
            skillLines = skillLines == null ? List.of() : List.copyOf(skillLines);
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /**
     * A job, project, degree or volunteer role.
     *
     * @param title        position / degree / project name
     * @param organisation employer / institution / role on the project
     * @param dates        formatted, e.g. "May 2024 - Present" (empty if unknown)
     * @param details      extra lines under the heading (grade, courses)
     */
    public record Entry(String id, String title, String organisation, String location, String startDate,
                        String endDate, String dates, List<String> details, List<DocBullet> bullets) {
        public Entry {
            details = details == null ? List.of() : List.copyOf(details);
            bullets = bullets == null ? List.of() : List.copyOf(bullets);
        }

        /** "Position, Employer, City" */
        public String headline() {
            List<String> parts = new ArrayList<>();
            for (String p : new String[] {title, organisation, location}) {
                if (p != null && !p.isBlank()) {
                    parts.add(p);
                }
            }
            return String.join(", ", parts);
        }
    }

    /** @param anchor HTML id for "jump to"; @param sourceIds master highlight IDs (P3) */
    public record DocBullet(String anchor, String text, List<String> sourceIds) {
        public DocBullet {
            sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
        }
    }

    public record SkillLine(String category, List<String> items) {
        public SkillLine {
            items = List.copyOf(items);
        }

        public String text() {
            return category + ": " + String.join(", ", items);
        }
    }

    // ---------------------------------------------------------------- text views

    public List<String> headings() {
        return sections.stream().map(Section::heading).toList();
    }

    /** Name, contact line and work-authorisation line: the "header block" SG-03 and SG-06 look at. */
    public String headerText() {
        StringBuilder sb = new StringBuilder(header.name()).append('\n');
        sb.append(contactLine()).append('\n');
        if (header.workAuthLine() != null) {
            sb.append(header.workAuthLine()).append('\n');
        }
        return sb.toString();
    }

    public String contactLine() {
        return String.join(" | ", header.contacts().stream().map(Contact::label).toList());
    }

    /**
     * The document as plain text in reading order, the same order the PDF and DOCX put it in. Checks read
     * this; ATS round-trip checks compare it with what is extracted from the exported file.
     */
    public String plainText() {
        StringBuilder sb = new StringBuilder(headerText());
        for (Section section : sections) {
            sb.append('\n').append(section.heading()).append('\n');
            section.lines().forEach(l -> sb.append(l).append('\n'));
            for (Entry e : section.entries()) {
                sb.append(e.headline());
                if (!e.dates().isEmpty()) {
                    sb.append("    ").append(e.dates());
                }
                sb.append('\n');
                e.details().forEach(d -> sb.append(d).append('\n'));
                e.bullets().forEach(b -> sb.append("• ").append(b.text()).append('\n'));
            }
            section.skillLines().forEach(s -> sb.append(s.text()).append('\n'));
        }
        return sb.toString();
    }

    /** Body text only (no header), for page-length estimates. */
    public String bodyText() {
        return plainText().substring(headerText().length());
    }
}
