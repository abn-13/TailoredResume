package com.bu.tailorsg.checks;

import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeDocument.Entry;
import com.bu.tailorsg.render.ResumeDocument.Kind;
import com.bu.tailorsg.render.ResumeDocument.Section;

import java.util.ArrayList;
import java.util.List;

/** Builds {@link CheckContext}s from rendered documents, so every caller derives bullets the same way. */
public final class CheckContexts {

    private CheckContexts() {}

    /**
     * A context for a rendered resume (master or tailored).
     *
     * @param showWorkAuth what the user asked for (SG-08 compares it with what was rendered)
     */
    public static CheckContext.Builder fromDocument(CheckMode mode, MasterResume master, ResumeDocument doc,
                                                    CheckThresholds thresholds, int pageTarget, boolean showWorkAuth) {
        String summary = doc.sections().stream().filter(s -> s.kind() == Kind.SUMMARY)
                .flatMap(s -> s.lines().stream()).findFirst().orElse("");
        String label = master.basics().label() == null ? "" : master.basics().label();
        return CheckContext.builder(mode, master, thresholds)
                .document(doc)
                .text(doc.plainText())
                .headerText(doc.headerText())
                .titleLine(label + "\n" + summary)
                .bullets(bullets(doc))
                .entries(entries(doc))
                .headings(doc.headings())
                .pageTarget(pageTarget)
                .showWorkAuth(showWorkAuth)
                .workAuthLine(doc.header().workAuthLine());
    }

    public static List<Bullet> bullets(ResumeDocument doc) {
        List<Bullet> bullets = new ArrayList<>();
        for (Section section : doc.sections()) {
            Bullet.Kind kind = switch (section.kind()) {
                case EXPERIENCE, VOLUNTEERING -> Bullet.Kind.EXPERIENCE;
                case PROJECTS -> Bullet.Kind.PROJECT;
                default -> Bullet.Kind.OTHER;
            };
            for (Entry e : section.entries()) {
                for (int i = 0; i < e.bullets().size(); i++) {
                    var b = e.bullets().get(i);
                    bullets.add(new Bullet(b.anchor(), e.id() + " bullet " + (i + 1), kind, e.id(), i,
                            e.endDate() == null, b.text(), b.sourceIds()));
                }
            }
        }
        return bullets;
    }

    public static List<DatedEntry> entries(ResumeDocument doc) {
        List<DatedEntry> entries = new ArrayList<>();
        for (Section section : doc.sections()) {
            if (section.kind() == Kind.EXPERIENCE || section.kind() == Kind.EDUCATION || section.kind() == Kind.VOLUNTEERING) {
                for (Entry e : section.entries()) {
                    entries.add(new DatedEntry(section.heading(), e.id(), e.headline(), e.startDate(), e.endDate()));
                }
            }
        }
        return entries;
    }
}
