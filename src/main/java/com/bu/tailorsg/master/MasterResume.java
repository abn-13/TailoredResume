package com.bu.tailorsg.master;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Typed, read-only view of a master resume (schema: {@code schema/master-resume.schema.json}).
 *
 * <p>The stored form is the validated JSON document itself; these records are how Java code reads it
 * (integrity checks now, renderer and CheckEngine later). Field names match the JSON keys exactly so
 * Jackson maps them without annotations.
 *
 * <p>Each record's compact constructor turns missing lists into empty ones, so callers never null-check
 * a list. {@code @JsonInclude(NON_EMPTY)} drops empty lists and nulls when writing back to JSON, so a
 * round trip does not add keys that were not there.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record MasterResume(
        Meta meta,
        Basics basics,
        List<Experience> work,
        List<Project> projects,
        List<Education> education,
        List<Skill> skills,
        List<Certificate> certificates,
        List<Award> awards,
        List<Language> languages,
        List<Experience> volunteer,
        List<Extra> extras) {

    public MasterResume {
        work = orEmpty(work);
        projects = orEmpty(projects);
        education = orEmpty(education);
        skills = orEmpty(skills);
        certificates = orEmpty(certificates);
        awards = orEmpty(awards);
        languages = orEmpty(languages);
        volunteer = orEmpty(volunteer);
        extras = orEmpty(extras);
    }

    /**
     * Every highlight in the resume keyed by its ID, in document order. This is the lookup that
     * provenance checks use: a bullet's sourceIds must resolve here.
     */
    public Map<String, Highlight> highlightsById() {
        Map<String, Highlight> byId = new LinkedHashMap<>();
        Stream.of(
                        work.stream().flatMap(w -> w.highlights().stream()),
                        projects.stream().flatMap(p -> p.highlights().stream()),
                        education.stream().flatMap(e -> e.highlights().stream()),
                        volunteer.stream().flatMap(v -> v.highlights().stream()))
                .flatMap(s -> s)
                .forEach(h -> byId.put(h.id(), h));
        return byId;
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Meta(int version, String updatedAt, String locale, String spelling) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Basics(
            String name,
            String label,
            String email,
            String phone,
            String url,
            Location location,
            List<Profile> profiles,
            List<SummaryVariant> summaryVariants,
            WorkAuthorization workAuthorization) {

        public Basics {
            profiles = orEmpty(profiles);
            summaryVariants = orEmpty(summaryVariants);
        }
    }

    public record Location(String city, String countryCode) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Profile(String network, String username, String url) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record SummaryVariant(String id, String text, List<String> targetRoles) {
        public SummaryVariant {
            targetRoles = orEmpty(targetRoles);
        }
    }

    public record WorkAuthorization(Status status, String displayText, boolean showOnResumeDefault) {
        public enum Status { EP_REQUIRED, EP_HOLDER, PR, CITIZEN, OTHER }
    }

    /**
     * A work or volunteer entry. {@code name} is the employer / organisation (JSON Resume naming).
     * {@code endDate == null} means current.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Experience(
            String id,
            String name,
            String position,
            String location,
            String employmentType,
            String url,
            String startDate,
            String endDate,
            String summary,
            List<Highlight> highlights) {

        public Experience {
            highlights = orEmpty(highlights);
        }

        /** {@code @JsonIgnore}: otherwise Jackson treats isX() as a property and writes "current" into the JSON. */
        @JsonIgnore
        public boolean isCurrent() {
            return endDate == null;
        }
    }

    /**
     * One achievement. The unit of provenance: generated bullets cite highlight IDs (P3).
     *
     * @param evidence private note for the user, never rendered
     * @param priority 1 (most important) to 5, or null if not set
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Highlight(
            String id,
            String text,
            List<Metric> metrics,
            List<String> skills,
            List<String> tags,
            String evidence,
            Integer priority) {

        public Highlight {
            metrics = orEmpty(metrics);
            skills = orEmpty(skills);
            tags = orEmpty(tags);
        }
    }

    /** A number exactly as the user wrote it, e.g. value "35", unit "%", label "latency reduction". */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Metric(String value, String unit, String label) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Project(
            String id,
            String name,
            String role,
            String url,
            String repo,
            String startDate,
            String endDate,
            String description,
            List<Highlight> highlights,
            List<String> keywords) {

        public Project {
            highlights = orEmpty(highlights);
            keywords = orEmpty(keywords);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Education(
            String id,
            String institution,
            String area,
            String studyType,
            String startDate,
            String endDate,
            String score,
            String scoreScale,
            List<String> courses,
            List<Highlight> highlights) {

        public Education {
            courses = orEmpty(courses);
            highlights = orEmpty(highlights);
        }
    }

    /** @param evidenceIds highlight IDs proving the skill; empty means "unevidenced" (allowed, but flagged) */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Skill(String id, String name, String category, String level, List<String> evidenceIds) {
        public Skill {
            evidenceIds = orEmpty(evidenceIds);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Certificate(String id, String name, String issuer, String date, String url) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Award(String id, String title, String awarder, String date, String summary) {}

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Language(String language, String fluency) {}

    /** @param type publication | leadership | cca | other */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Extra(String id, String type, String text, String date) {}

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }
}
