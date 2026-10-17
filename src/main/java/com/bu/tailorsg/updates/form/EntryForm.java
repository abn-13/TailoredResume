package com.bu.tailorsg.updates.form;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/**
 * The typed entries of the "Add Info" form (spec 3: work | project | education | skill | certificate |
 * award | highlight). The same classes back the HTML forms (Spring binds request parameters through the
 * setters) and {@code POST /api/updates/form} (Jackson picks the subclass from the {@code "type"} field).
 *
 * <p>Plain JavaBeans rather than records: Spring MVC form binding and Thymeleaf's {@code th:field} both
 * work through getters and setters. Field-level rules use Bean Validation annotations; rules that need
 * the current master resume (duplicates, unknown IDs) live in {@link FormPatchBuilder}.
 *
 * <p>{@code sealed}: the compiler knows every subtype, so the {@code switch} in FormPatchBuilder must handle
 * them all, with no default branch.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EntryForm.Work.class, name = "work"),
        @JsonSubTypes.Type(value = EntryForm.Project.class, name = "project"),
        @JsonSubTypes.Type(value = EntryForm.Education.class, name = "education"),
        @JsonSubTypes.Type(value = EntryForm.Skill.class, name = "skill"),
        @JsonSubTypes.Type(value = EntryForm.Certificate.class, name = "certificate"),
        @JsonSubTypes.Type(value = EntryForm.Award.class, name = "award"),
        @JsonSubTypes.Type(value = EntryForm.Highlight.class, name = "highlight"),
        @JsonSubTypes.Type(value = EntryForm.Metric.class, name = "metric")})
public abstract sealed class EntryForm {

    static final String YEAR_MONTH = "^$|^[0-9]{4}-(0[1-9]|1[0-2])$";
    static final String YEAR_MONTH_MESSAGE = "Use YYYY-MM, e.g. 2024-05";
    static final String URL_PATTERN = "^$|^https?://\\S+$";
    static final String URL_MESSAGE = "Use a full URL starting with https://";

    /** The form types in tab order, as used in URLs (/add-info/work) and the JSON "type" field. */
    public static final List<String> TYPES =
            List.of("work", "project", "education", "skill", "certificate", "award", "highlight");

    /** This form's type as used in URLs and JSON, e.g. "work". (Not a getter, so Jackson ignores it.) */
    public String typeName() {
        return switch (this) {
            case Work w -> "work";
            case Project p -> "project";
            case Education e -> "education";
            case Skill s -> "skill";
            case Certificate c -> "certificate";
            case Award a -> "award";
            case Highlight h -> "highlight";
            case Metric m -> "metric";
        };
    }

    public static EntryForm empty(String type) {
        return switch (type) {
            case "work" -> new Work();
            case "project" -> new Project();
            case "education" -> new Education();
            case "skill" -> new Skill();
            case "certificate" -> new Certificate();
            case "award" -> new Award();
            case "highlight" -> new Highlight();
            case "metric" -> new Metric();
            default -> throw new IllegalArgumentException("Unknown entry type: " + type);
        };
    }

    // ---------------------------------------------------------------- work

    public static final class Work extends EntryForm {
        @NotBlank(message = "Employer is required")
        private String name;
        @NotBlank(message = "Job title is required")
        private String position;
        private String location;
        @Pattern(regexp = "^$|Internship|Full-time|Contract|Part-time", message = "Choose an employment type")
        private String employmentType;
        @Pattern(regexp = URL_PATTERN, message = URL_MESSAGE)
        private String url;
        @NotBlank(message = "Start date is required")
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String startDate;
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String endDate;
        private String summary;
        /** One highlight per line; leading bullet characters are removed. */
        @Size(max = 5000, message = "Keep highlights under 5000 characters")
        private String highlights;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getPosition() { return position; }
        public void setPosition(String position) { this.position = position; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getEmploymentType() { return employmentType; }
        public void setEmploymentType(String employmentType) { this.employmentType = employmentType; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getHighlights() { return highlights; }
        public void setHighlights(String highlights) { this.highlights = highlights; }
    }

    // ---------------------------------------------------------------- project

    public static final class Project extends EntryForm {
        @NotBlank(message = "Project name is required")
        private String name;
        private String role;
        @Pattern(regexp = URL_PATTERN, message = URL_MESSAGE)
        private String url;
        @Pattern(regexp = URL_PATTERN, message = URL_MESSAGE)
        private String repo;
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String startDate;
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String endDate;
        private String description;
        /** Comma-separated. */
        private String keywords;
        @Size(max = 5000, message = "Keep highlights under 5000 characters")
        private String highlights;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getRepo() { return repo; }
        public void setRepo(String repo) { this.repo = repo; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getKeywords() { return keywords; }
        public void setKeywords(String keywords) { this.keywords = keywords; }
        public String getHighlights() { return highlights; }
        public void setHighlights(String highlights) { this.highlights = highlights; }
    }

    // ---------------------------------------------------------------- education

    public static final class Education extends EntryForm {
        @NotBlank(message = "Institution is required")
        private String institution;
        @NotBlank(message = "Qualification is required, e.g. Master of Engineering")
        private String studyType;
        private String area;
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String startDate;
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String endDate;
        private String score;
        private String scoreScale;
        /** Comma-separated. */
        private String courses;

        public String getInstitution() { return institution; }
        public void setInstitution(String institution) { this.institution = institution; }
        public String getStudyType() { return studyType; }
        public void setStudyType(String studyType) { this.studyType = studyType; }
        public String getArea() { return area; }
        public void setArea(String area) { this.area = area; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public String getScore() { return score; }
        public void setScore(String score) { this.score = score; }
        public String getScoreScale() { return scoreScale; }
        public void setScoreScale(String scoreScale) { this.scoreScale = scoreScale; }
        public String getCourses() { return courses; }
        public void setCourses(String courses) { this.courses = courses; }
    }

    // ---------------------------------------------------------------- skill

    public static final class Skill extends EntryForm {
        @NotBlank(message = "Skill name is required")
        private String name;
        @NotBlank(message = "Choose a category")
        @Pattern(regexp = SkillCategories.REGEX, message = "Choose a category")
        private String category;
        private String level;
        /** Highlight IDs that prove the skill. */
        private List<String> evidenceIds = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public List<String> getEvidenceIds() { return evidenceIds; }
        public void setEvidenceIds(List<String> evidenceIds) { this.evidenceIds = evidenceIds == null ? new ArrayList<>() : evidenceIds; }
    }

    // ---------------------------------------------------------------- certificate

    public static final class Certificate extends EntryForm {
        @NotBlank(message = "Certificate name is required")
        private String name;
        @NotBlank(message = "Issuer is required")
        private String issuer;
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String date;
        @Pattern(regexp = URL_PATTERN, message = URL_MESSAGE)
        private String url;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    // ---------------------------------------------------------------- award

    public static final class Award extends EntryForm {
        @NotBlank(message = "Award title is required")
        private String title;
        private String awarder;
        @Pattern(regexp = YEAR_MONTH, message = YEAR_MONTH_MESSAGE)
        private String date;
        private String summary;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getAwarder() { return awarder; }
        public void setAwarder(String awarder) { this.awarder = awarder; }
        public String getDate() { return date; }
        public void setDate(String date) { this.date = date; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
    }

    // ---------------------------------------------------------------- highlight

    /**
     * One achievement added to an existing job, project, education or volunteer entry. Selecting skills
     * also records this highlight as evidence for them; "new skills" are created with it as evidence.
     */
    public static final class Highlight extends EntryForm {
        @NotBlank(message = "Choose where this highlight belongs")
        private String parentId;
        @NotBlank(message = "Describe the achievement")
        @Size(max = 600, message = "Keep a highlight under 600 characters")
        private String text;
        private String metricValue;
        private String metricUnit;
        private String metricLabel;
        /** Names of existing skills this highlight demonstrates. */
        private List<String> skills = new ArrayList<>();
        /** Up to three skills to create, each with this highlight as evidence. */
        @Valid
        private List<NewSkill> newSkills = new ArrayList<>(List.of(new NewSkill(), new NewSkill(), new NewSkill()));
        /** Comma-separated. */
        private String tags;
        @Min(value = 1, message = "Priority is 1 (highest) to 5")
        @Max(value = 5, message = "Priority is 1 (highest) to 5")
        private Integer priority;

        public String getParentId() { return parentId; }
        public void setParentId(String parentId) { this.parentId = parentId; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getMetricValue() { return metricValue; }
        public void setMetricValue(String metricValue) { this.metricValue = metricValue; }
        public String getMetricUnit() { return metricUnit; }
        public void setMetricUnit(String metricUnit) { this.metricUnit = metricUnit; }
        public String getMetricLabel() { return metricLabel; }
        public void setMetricLabel(String metricLabel) { this.metricLabel = metricLabel; }
        public List<String> getSkills() { return skills; }
        public void setSkills(List<String> skills) { this.skills = skills == null ? new ArrayList<>() : skills; }
        public List<NewSkill> getNewSkills() { return newSkills; }
        public void setNewSkills(List<NewSkill> newSkills) { this.newSkills = newSkills == null ? new ArrayList<>() : newSkills; }
        public String getTags() { return tags; }
        public void setTags(String tags) { this.tags = tags; }
        public Integer getPriority() { return priority; }
        public void setPriority(Integer priority) { this.priority = priority; }
    }

    /**
     * "Add metric" quick fix (spec 7 Master health, LINT-02): attach a number the user knows to an existing
     * highlight, and update its wording to mention it. Reached from a LINT-02 finding, not from the tabs.
     */
    public static final class Metric extends EntryForm {
        @NotBlank(message = "Which highlight?")
        private String highlightId;
        @NotBlank(message = "Enter the number, exactly as you would write it")
        private String value;
        private String unit;
        @NotBlank(message = "Say what the number measures, e.g. latency reduction")
        private String label;
        /** The highlight text, edited to include the number (prefilled with the current text). */
        @NotBlank(message = "Keep the highlight text")
        @Size(max = 600, message = "Keep a highlight under 600 characters")
        private String text;

        public String getHighlightId() { return highlightId; }
        public void setHighlightId(String highlightId) { this.highlightId = highlightId; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    /** A skill to create from the highlight form. Blank rows are ignored. */
    public static final class NewSkill {
        private String name;
        @Pattern(regexp = "^$|" + SkillCategories.REGEX, message = "Choose a category")
        private String category;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }
}
