package com.bu.tailorsg.render;

import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResume.Basics;
import com.bu.tailorsg.master.MasterResume.Education;
import com.bu.tailorsg.master.MasterResume.Experience;
import com.bu.tailorsg.master.MasterResume.Highlight;
import com.bu.tailorsg.master.MasterResume.Project;
import com.bu.tailorsg.render.ResumeDocument.Contact;
import com.bu.tailorsg.render.ResumeDocument.DocBullet;
import com.bu.tailorsg.render.ResumeDocument.Entry;
import com.bu.tailorsg.render.ResumeDocument.Header;
import com.bu.tailorsg.render.ResumeDocument.Kind;
import com.bu.tailorsg.render.ResumeDocument.Section;
import com.bu.tailorsg.render.ResumeDocument.SkillLine;
import com.bu.tailorsg.updates.form.SkillCategories;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link ResumeDocument}s. Structural facts (name, contact, employers, titles, locations, dates,
 * degrees, institutions, grades) always come from the master resume, never from model text (P2).
 *
 * <p>This class renders the whole master (Master health). {@link TailoredRenderer} renders a tailored
 * resume using the shared pieces here.
 */
@Component
public class ResumeRenderer {

    /** Everything in the master, in stored order: the input for the Master health checks. */
    public ResumeDocument renderMaster(MasterResume m) {
        List<Section> sections = new ArrayList<>();
        List<String> summaries = m.basics().summaryVariants().stream().map(MasterResume.SummaryVariant::text).toList();
        if (!summaries.isEmpty()) {
            sections.add(new Section(Kind.SUMMARY, "Summary", null, null, summaries));
        }
        if (!m.work().isEmpty()) {
            sections.add(new Section(Kind.EXPERIENCE, "Experience",
                    m.work().stream().map(w -> experience(w, allBullets(w.highlights()))).toList(), null, null));
        }
        if (!m.projects().isEmpty()) {
            sections.add(new Section(Kind.PROJECTS, "Projects",
                    m.projects().stream().map(p -> project(p, allBullets(p.highlights()))).toList(), null, null));
        }
        if (!m.education().isEmpty()) {
            sections.add(new Section(Kind.EDUCATION, "Education",
                    m.education().stream().map(e -> education(e, allBullets(e.highlights()))).toList(), null, null));
        }
        if (!m.skills().isEmpty()) {
            sections.add(new Section(Kind.SKILLS, "Skills", null, skillLinesByCategory(m), null));
        }
        if (!m.volunteer().isEmpty()) {
            sections.add(new Section(Kind.VOLUNTEERING, "Volunteering",
                    m.volunteer().stream().map(v -> experience(v, allBullets(v.highlights()))).toList(), null, null));
        }
        List<String> certs = certificateLines(m);
        if (!certs.isEmpty()) {
            sections.add(new Section(Kind.CERTIFICATIONS, "Certifications", null, null, certs));
        }
        List<String> awards = awardLines(m);
        if (!awards.isEmpty()) {
            sections.add(new Section(Kind.AWARDS, "Awards", null, null, awards));
        }
        if (!m.languages().isEmpty()) {
            sections.add(new Section(Kind.LANGUAGES, "Languages", null, null, List.of(String.join(", ",
                    m.languages().stream().map(l -> l.fluency() == null ? l.language() : l.language() + " (" + l.fluency() + ")").toList()))));
        }
        return new ResumeDocument(header(m.basics(), m.basics().workAuthorization() != null
                && m.basics().workAuthorization().showOnResumeDefault()), sections);
    }

    // ---------------------------------------------------------------- shared pieces (also used by TailoredRenderer)

    public Header header(Basics b, boolean showWorkAuth) {
        List<Contact> contacts = new ArrayList<>();
        if (b.phone() != null) {
            contacts.add(new Contact(b.phone(), "tel:" + b.phone()));
        }
        contacts.add(new Contact(b.email(), "mailto:" + b.email()));
        // Spec 4 order: phone | email | LinkedIn | GitHub | portfolio
        for (String network : List.of("LinkedIn", "GitHub", "Portfolio", "Other")) {
            b.profiles().stream().filter(p -> network.equals(p.network()))
                    .forEach(p -> contacts.add(new Contact(visibleUrl(p.url()), p.url())));
        }
        if (b.url() != null && b.profiles().stream().noneMatch(p -> b.url().equals(p.url()))) {
            contacts.add(new Contact(visibleUrl(b.url()), b.url()));
        }
        if (b.location() != null && b.location().city() != null) {
            contacts.add(new Contact(b.location().city(), null));
        }
        String auth = showWorkAuth && b.workAuthorization() != null ? b.workAuthorization().displayText() : null;
        return new Header(b.name(), contacts, auth);
    }

    public Entry experience(Experience w, List<DocBullet> bullets) {
        return new Entry(w.id(), w.position(), w.name(), w.location(), w.startDate(), w.endDate(),
                ResumeDates.range(w.startDate(), w.endDate()), List.of(), bullets);
    }

    public Entry project(Project p, List<DocBullet> bullets) {
        return new Entry(p.id(), p.name(), p.role(), null, p.startDate(), p.endDate(),
                ResumeDates.range(p.startDate(), p.endDate()), List.of(), bullets);
    }

    public Entry education(Education e, List<DocBullet> bullets) {
        String title = e.area() == null ? e.studyType() : e.studyType() + " (" + e.area() + ")";
        List<String> details = new ArrayList<>();
        if (e.score() != null) {
            details.add("Grade: " + e.score() + (e.scoreScale() == null ? "" : " / " + e.scoreScale()));
        }
        if (!e.courses().isEmpty()) {
            details.add("Relevant courses: " + String.join(", ", e.courses()));
        }
        return new Entry(e.id(), title, e.institution(), null, e.startDate(), e.endDate(),
                ResumeDates.range(e.startDate(), e.endDate()), details, bullets);
    }

    public List<SkillLine> skillLinesByCategory(MasterResume m) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        SkillCategories.ALL.forEach(c -> groups.put(c, new ArrayList<>()));
        m.skills().forEach(s -> groups.computeIfAbsent(s.category(), c -> new ArrayList<>()).add(s.name()));
        List<SkillLine> lines = new ArrayList<>();
        groups.forEach((category, names) -> {
            if (!names.isEmpty()) {
                lines.add(new SkillLine(category, names));
            }
        });
        return lines;
    }

    public List<String> certificateLines(MasterResume m) {
        return m.certificates().stream().map(c -> c.name() + ", " + c.issuer()
                + (c.date() == null ? "" : " (" + ResumeDates.monthYear(c.date()) + ")")).toList();
    }

    public List<String> awardLines(MasterResume m) {
        return m.awards().stream().map(a -> a.title() + (a.awarder() == null ? "" : ", " + a.awarder())
                + (a.date() == null ? "" : " (" + ResumeDates.monthYear(a.date()) + ")")).toList();
    }

    private static List<DocBullet> allBullets(List<Highlight> highlights) {
        return highlights.stream().map(h -> new DocBullet("b-" + h.id(), h.text(), List.of(h.id()))).toList();
    }

    /** "https://www.github.com/jordan/" becomes "github.com/jordan". */
    public static String visibleUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceFirst("/$", "");
    }
}
