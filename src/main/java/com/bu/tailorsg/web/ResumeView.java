package com.bu.tailorsg.web;

import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResume.Metric;
import com.bu.tailorsg.master.MasterResume.Skill;
import com.bu.tailorsg.updates.form.SkillCategories;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Helpers for the readable master resume view ({@code templates/fragments/resume-view.html}).
 * Templates call them as {@code ${@resumeView.dates(start, end)}}: {@code @name} is a Spring bean
 * reference in Thymeleaf's Spring expressions.
 */
@Component("resumeView")
public class ResumeView {

    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.UK);

    /** Spec section 4 date style: "May 2024 - Dec 2025" or "May 2024 - Present" (ASCII hyphen). */
    public String dates(String start, String end) {
        if (start == null && end == null) {
            return "";
        }
        return monthYear(start) + " - " + (end == null ? "Present" : monthYear(end));
    }

    public String monthYear(String yearMonth) {
        if (yearMonth == null) {
            return "?";
        }
        try {
            return YearMonth.parse(yearMonth).format(MONTH_YEAR);
        } catch (RuntimeException e) {
            return yearMonth; // already validated in practice; show as-is rather than failing the page
        }
    }

    /**
     * The URL if it is http(s), else null. Stored master data is already validated, but previews of an
     * edited patch are not, and Thymeleaf would happily render a {@code javascript:} link.
     */
    /** The resume file name an export would get (spec 4: Firstname_Lastname_Resume_Company.pdf). */
    public String exportName(com.bu.tailorsg.render.ResumeDocument doc, String company) {
        return doc == null ? "" : com.bu.tailorsg.export.ExportService.baseName(doc.header().name()) + "_Resume_"
                + com.bu.tailorsg.export.ExportService.safe(company == null ? "Company" : company) + ".pdf";
    }

    /** "17 Oct 2026, 14:05" in the local time zone, for "updated" columns. */
    public String when(java.time.Instant instant) {
        return instant == null ? "" : DisplayFormats.dateTime(instant);
    }

    public String safeUrl(String url) {
        return url != null && url.matches("^https?://\\S+$") ? url : null;
    }

    /** e.g. "35% (p95 latency reduction)" */
    public String metric(Metric m) {
        String unit = "";
        if (m.unit() != null) {
            unit = m.unit().equals("%") ? "%" : " " + m.unit(); // "35%" but "3 hours"
        }
        return m.value() + unit + " (" + m.label() + ")";
    }

    /** Skills grouped by category in the schema's category order. */
    public Map<String, List<Skill>> skillsByCategory(MasterResume resume) {
        Map<String, List<Skill>> groups = new LinkedHashMap<>();
        SkillCategories.ALL.forEach(c -> groups.put(c, new ArrayList<>()));
        resume.skills().forEach(s -> groups.computeIfAbsent(s.category(), c -> new ArrayList<>()).add(s));
        groups.values().removeIf(List::isEmpty);
        return groups;
    }
}
