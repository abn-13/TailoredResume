package com.bu.tailorsg.fit;

import com.bu.tailorsg.applications.Application;
import com.bu.tailorsg.applications.ApplicationService;
import com.bu.tailorsg.checks.Severity;
import com.bu.tailorsg.checks.ThresholdService;
import com.bu.tailorsg.checks.kw.KeywordAnalysis;
import com.bu.tailorsg.checks.kw.KeywordAnalysis.Match;
import com.bu.tailorsg.checks.kw.KeywordAnalysis.TermResult;
import com.bu.tailorsg.checks.kw.KeywordAnalyzer;
import com.bu.tailorsg.checks.kw.KeywordMatcher;
import com.bu.tailorsg.fit.FitAdvice.GapPlan;
import com.bu.tailorsg.fit.FitAdvice.ProjectSuggestion;
import com.bu.tailorsg.fit.FitAdvice.Strength;
import com.bu.tailorsg.fit.FitFacts.Evidence;
import com.bu.tailorsg.fit.FitFacts.Flag;
import com.bu.tailorsg.fit.FitFacts.TermFit;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResume.WorkAuthorization;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.StoredVersion;
import com.bu.tailorsg.render.ResumeDates;
import com.bu.tailorsg.tailoring.TailoringService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Job fit (the user's add-on, Phase 5b): "how well does this job suit my profile, is it worth applying, or
 * should I build a small project first?"
 *
 * <p>Two layers, kept apart on purpose (P4). {@link #facts} is deterministic: evidence for each JD term,
 * years of experience, seniority and the Employment Pass salary floor. {@link #advise} asks the writer
 * model for a recommendation built on those facts, then verifies its answer in code before storing it.
 */
@Service
public class FitService {

    public static final String PROMPT = "fit-advice";
    static final int MAX_PROJECT_DAYS = 14;

    /** "3+ years", "2-4 years", "5 yrs", "minimum 3 years". */
    static final Pattern YEARS = Pattern.compile("(?i)(?<![\\d.])(\\d{1,2})\\s*\\+?\\s*(?:(?:-|to|–)\\s*\\d{1,2}\\s*)?\\+?\\s*(?:years?|yrs?)\\b");
    static final Pattern SOFTWARE_ROLE = Pattern.compile(
            "(?i)\\b(software|developer|programmer|programming|back[- ]?end|front[- ]?end|full[- ]?stack|devops|data|cloud|web|mobile|sre|qa|test|it|application|systems? analyst)\\b");
    static final Pattern SENIOR = Pattern.compile("(?i)\\b(senior|sr\\.?|lead|principal|staff|manager|head)\\b");

    private final ApplicationService applications;
    private final MasterResumeService masters;
    private final KeywordAnalyzer keywords;
    private final ThresholdService thresholds;
    private final ClaudeClient claude;
    private final FitProperties props;
    private final JsonMapper json;
    private final Clock clock;

    public FitService(ApplicationService applications, MasterResumeService masters, KeywordAnalyzer keywords,
                      ThresholdService thresholds, ClaudeClient claude, FitProperties props, JsonMapper json, Clock clock) {
        this.applications = applications;
        this.masters = masters;
        this.keywords = keywords;
        this.thresholds = thresholds;
        this.claude = claude;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- deterministic facts

    public Optional<FitFacts> facts(Application a) {
        return masters.currentResume().map(m -> facts(m, applications.specOf(a)));
    }

    public FitFacts facts(MasterResume m, JobSpec job) {
        // Empty resume text: every term is then classified only by where the master has it.
        KeywordAnalysis analysis = keywords.analyse(job, "", m, thresholds.current().kw());
        List<TermFit> required = analysis.required().stream().map(t -> termFit(t, m)).toList();
        List<TermFit> preferred = analysis.preferred().stream().map(t -> termFit(t, m)).toList();

        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Singapore")));
        Integer yearsAsked = yearsAsked(job);
        double total = years(m, false, today);
        double software = years(m, true, today);
        LocalDate start = startDate(m, today);

        List<Flag> flags = new ArrayList<>();
        double reqCov = coverage(required);
        if (!required.isEmpty() && reqCov < 0.5) {
            flags.add(new Flag("FIT-COVERAGE", Severity.WARN, "Your master shows evidence for less than half of the required skills ("
                    + required.stream().filter(TermFit::counts).count() + " of " + required.size() + ")."));
        }
        if (yearsAsked != null && software + 0.5 < yearsAsked) {
            flags.add(new Flag("FIT-YEARS", Severity.WARN, "The posting asks for " + yearsAsked + "+ years; your master shows "
                    + fmt(software) + " years in software roles (" + fmt(total) + " years of work in total, internships included)."));
        }
        String seniority = (job.seniority() == null ? "" : job.seniority()) + " " + (job.title() == null ? "" : job.title());
        if (SENIOR.matcher(seniority).find() && software < 3) {
            flags.add(new Flag("FIT-SENIORITY", Severity.WARN, "This reads as a senior role (\"" + seniority.strip()
                    + "\"), and your software experience is under 3 years."));
        }
        required.stream().filter(t -> t.evidence() == Evidence.UNEVIDENCED).forEach(t -> flags.add(new Flag("FIT-UNEVIDENCED",
                Severity.INFO, "\"" + t.term() + "\" is in your skills list but no highlight shows it. Add one if you can.")));
        epFlag(m, job, start).ifPresent(flags::add);

        return new FitFacts(required, preferred, reqCov, coverage(preferred), yearsAsked, total, software,
                ResumeDates.monthYear(YearMonth.from(start).toString()), flags);
    }

    private static TermFit termFit(TermResult t, MasterResume m) {
        if (t.match() == Match.GAP) {
            return new TermFit(t.term(), t.required(), Evidence.GAP, null, null);
        }
        if (t.match() == Match.MATCHED) {
            return new TermFit(t.term(), t.required(), Evidence.EVIDENCED, null, null);
        }
        String id = t.sourceId();
        if (id == null) {
            return new TermFit(t.term(), t.required(), Evidence.MENTIONED, null, t.sourceText());
        }
        if (id.startsWith("skill-")) {
            boolean evidenced = m.skills().stream().anyMatch(s -> s.id().equals(id) && !s.evidenceIds().isEmpty());
            return new TermFit(t.term(), t.required(), evidenced ? Evidence.LISTED : Evidence.UNEVIDENCED, id, t.sourceText());
        }
        return new TermFit(t.term(), t.required(), Evidence.EVIDENCED, id, t.sourceText());
    }

    static Integer yearsAsked(JobSpec job) {
        List<String> texts = new ArrayList<>(job.qualifications());
        texts.addAll(job.responsibilities());
        texts.addAll(job.keywordsRaw());
        if (job.seniority() != null) {
            texts.add(job.seniority());
        }
        Integer max = null;
        for (String text : texts) {
            Matcher m = YEARS.matcher(text);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n > 0 && n <= 20 && (max == null || n > max)) {
                    max = n;
                }
            }
        }
        return max;
    }

    /** Years of work, with overlapping entries merged so two part-time roles at once are not double counted. */
    static double years(MasterResume m, boolean softwareOnly, LocalDate today) {
        List<int[]> spans = new ArrayList<>();
        for (var w : m.work()) {
            if (w.startDate() == null || (softwareOnly && !SOFTWARE_ROLE.matcher(w.position() == null ? "" : w.position()).find())) {
                continue;
            }
            int start = monthIndex(w.startDate());
            int end = w.endDate() == null ? today.getYear() * 12 + today.getMonthValue() - 1 : monthIndex(w.endDate());
            if (end >= start) {
                spans.add(new int[] {start, end});
            }
        }
        spans.sort(Comparator.comparingInt(s -> s[0]));
        int months = 0;
        int curStart = -1;
        int curEnd = -2;
        for (int[] s : spans) {
            if (s[0] > curEnd + 1) {
                months += curEnd - curStart + 1;
                curStart = s[0];
                curEnd = s[1];
            } else {
                curEnd = Math.max(curEnd, s[1]);
            }
        }
        months += curEnd - curStart + 1;
        return Math.round(months / 12.0 * 10) / 10.0;
    }

    /** Earliest start: the end of a course still in progress (e.g. graduation), otherwise today. */
    static LocalDate startDate(MasterResume m, LocalDate today) {
        return m.education().stream().map(MasterResume.Education::endDate).filter(d -> d != null)
                .map(d -> YearMonth.parse(d.length() == 4 ? d + "-12" : d.substring(0, 7)).atDay(1))
                .filter(d -> d.isAfter(today)).max(Comparator.naturalOrder()).orElse(today);
    }

    private Optional<Flag> epFlag(MasterResume m, JobSpec job, LocalDate start) {
        WorkAuthorization auth = m.basics().workAuthorization();
        if (auth == null || auth.status() != WorkAuthorization.Status.EP_REQUIRED) {
            return Optional.empty();
        }
        var threshold = props.epThresholdOn(start).orElse(null);
        if (threshold == null) {
            return Optional.empty();
        }
        String floor = "S$" + String.format(Locale.ROOT, "%,d", threshold.general()) + " a month for new Employment Pass applications from "
                + threshold.from().getDayOfMonth() + " " + ResumeDates.monthYear(YearMonth.from(threshold.from()).toString())
                + " (S$" + String.format(Locale.ROOT, "%,d", threshold.financialServices()) + " in financial services; older applicants need more; "
                + "check " + props.epSourceUrl() + ")";
        var salary = job.salaryRange();
        if (salary == null || (salary.min() == null && salary.max() == null)) {
            return Optional.of(new Flag("FIT-EP-SALARY", Severity.INFO, "No salary stated. You will need an Employment Pass, "
                    + "so the offer must be at least " + floor + "."));
        }
        String currency = salary.currency() == null ? "" : salary.currency().strip().toUpperCase(Locale.ROOT);
        if (!currency.isEmpty() && !currency.equals("SGD") && !currency.equals("S$")) {
            return Optional.of(new Flag("FIT-EP-SALARY", Severity.INFO, "Salary is in " + currency + "; compare it yourself with " + floor + "."));
        }
        double max = monthly(salary.max() != null ? salary.max() : salary.min());
        double min = monthly(salary.min() != null ? salary.min() : salary.max());
        if (max < threshold.general()) {
            return Optional.of(new Flag("FIT-EP-SALARY", Severity.WARN, "The posted salary (up to S$" + String.format(Locale.ROOT, "%,.0f", max)
                    + " a month) is below the Employment Pass minimum of " + floor + "."));
        }
        if (min < threshold.general()) {
            return Optional.of(new Flag("FIT-EP-SALARY", Severity.INFO, "The lower end of the range is below the Employment Pass minimum of "
                    + floor + "; an offer would need to be at the higher end."));
        }
        return Optional.empty();
    }

    /** Postings above S$40k are almost certainly annual figures. */
    private static double monthly(double value) {
        return value > 40_000 ? value / 12 : value;
    }

    // ---------------------------------------------------------------- advisory narrative

    public Optional<FitAdvice.Stored> adviceOf(Application a) {
        return Optional.ofNullable(a.getFitAssessment()).map(s -> json.readValue(s, FitAdvice.Stored.class));
    }

    /** Asks the writer model for advice on top of the facts, verifies it, and stores it on the application. */
    public FitAdvice.Stored advise(long applicationId) {
        Application a = applications.get(applicationId);
        StoredVersion version = masters.current().orElseThrow(() -> new IllegalStateException("Create your master resume first."));
        MasterResume m = masters.asResume(version.json()).orElseThrow();
        JobSpec job = applications.specOf(a);
        FitFacts facts = facts(m, job);

        LlmResult result = claude.call(PROMPT, List.of(
                new TextBlock("<MASTER>\n" + json.writeValueAsString(TailoringService.forModel(version.json())) + "\n</MASTER>", true),
                TextBlock.of("<JOB>\n" + json.writeValueAsString(job) + "\n</JOB>"),
                TextBlock.of("<FACTS>\n" + json.writeValueAsString(factsForModel(facts)) + "\n</FACTS>")));
        List<String> notes = new ArrayList<>();
        FitAdvice verified = verify(json.treeToValue(result.json(), FitAdvice.class), facts, m, keywords.matcher(), notes);
        FitAdvice.Stored stored = new FitAdvice.Stored(verified, notes, result.model(), result.promptVersion(),
                version.versionNo(), clock.instant());
        a.setFitAssessment(json.writeValueAsString(stored), clock.instant());
        applications.setPromptVersion(a, PROMPT, result.promptVersion());
        applications.save(a);
        return stored;
    }

    static Map<String, Object> factsForModel(FitFacts f) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requiredCoverage", Math.round(f.requiredCoverage() * 100) + "%");
        out.put("preferredCoverage", Math.round(f.preferredCoverage() * 100) + "%");
        out.put("required", f.required().stream().map(t -> Map.of("term", t.term(), "evidence", t.evidence().name(),
                "sourceId", t.sourceId() == null ? "" : t.sourceId())).toList());
        out.put("preferred", f.preferred().stream().map(t -> Map.of("term", t.term(), "evidence", t.evidence().name(),
                "sourceId", t.sourceId() == null ? "" : t.sourceId())).toList());
        out.put("gaps", f.gaps());
        out.put("yearsAsked", f.yearsAsked() == null ? "not stated" : f.yearsAsked());
        out.put("softwareYears", f.softwareYears());
        out.put("totalYears", f.totalYears());
        out.put("earliestStart", f.startDate());
        out.put("flags", f.flags().stream().map(Flag::message).toList());
        return out;
    }

    /**
     * Holds the model to the facts. Removes (with a note) anything that does not rest on them: strengths
     * without a real citation or that name a gap, "gaps" that are not gaps, and a project that targets none.
     */
    static FitAdvice verify(FitAdvice raw, FitFacts facts, MasterResume m, KeywordMatcher matcher, List<String> notes) {
        Set<String> knownIds = new HashSet<>(m.highlightsById().keySet());
        m.basics().summaryVariants().forEach(s -> knownIds.add(s.id()));
        List<String> gapTerms = new ArrayList<>(facts.gaps());
        java.util.stream.Stream.concat(facts.required().stream(), facts.preferred().stream())
                .filter(t -> t.evidence() == Evidence.UNEVIDENCED).forEach(t -> gapTerms.add(t.term()));

        List<Strength> strengths = new ArrayList<>();
        for (Strength s : raw.strengths()) {
            List<String> ids = s.sourceIds().stream().filter(knownIds::contains).toList();
            Optional<String> namesGap = gapTerms.stream().filter(g -> matcher.term(JobSpec.JdSkill.of(g)).foundIn(s.point())).findFirst();
            if (ids.isEmpty()) {
                notes.add("Removed a strength that cites nothing in your master: \"" + s.point() + "\"");
            } else if (namesGap.isPresent()) {
                notes.add("Removed a strength that implies a skill you have no evidence for (" + namesGap.get() + "): \"" + s.point() + "\"");
            } else {
                strengths.add(new Strength(s.point(), ids));
            }
        }

        List<GapPlan> gaps = new ArrayList<>();
        for (GapPlan g : raw.gapsToAddress()) {
            if (matchesAny(g.term(), gapTerms, matcher)) {
                gaps.add(g);
            } else {
                notes.add("Removed \"" + g.term() + "\" from the gaps: your master already shows it, or the job does not ask for it.");
            }
        }

        ProjectSuggestion project = raw.projectSuggestion();
        if (project != null && project.isPresent()) {
            List<String> targets = project.targetsGaps().stream().filter(t -> matchesAny(t, gapTerms, matcher)).toList();
            if (targets.isEmpty()) {
                notes.add("Removed the project suggestion \"" + project.title() + "\": it does not target any gap in the facts.");
                project = null;
            } else {
                if (targets.size() < project.targetsGaps().size()) {
                    notes.add("The project's targets were narrowed to real gaps: " + String.join(", ", targets) + ".");
                }
                if (project.estimatedDays() > MAX_PROJECT_DAYS) {
                    notes.add("The project estimate (" + project.estimatedDays() + " days) is over the 2-week limit; cut its scope.");
                }
                project = new ProjectSuggestion(project.title(), targets, project.description(), project.scope(),
                        project.estimatedDays(), project.showcase());
            }
        } else {
            project = null;
        }
        if (raw.recommendation() == FitAdvice.Recommendation.BUILD_PROJECT_FIRST && project == null) {
            notes.add("The advice recommends a project first, but no valid project is left after verification.");
        }
        return new FitAdvice(raw.recommendation(), raw.headline(), raw.reasons(), strengths, gaps,
                project == null ? new ProjectSuggestion("", List.of(), "", List.of(), 0, "") : project);
    }

    private static boolean matchesAny(String text, List<String> gapTerms, KeywordMatcher matcher) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return gapTerms.stream().anyMatch(g -> g.equalsIgnoreCase(text.strip())
                || matcher.term(JobSpec.JdSkill.of(g)).foundIn(text) || matcher.term(JobSpec.JdSkill.of(text.strip())).foundIn(g));
    }

    private static double coverage(List<TermFit> terms) {
        return terms.isEmpty() ? 1.0 : terms.stream().filter(TermFit::counts).count() / (double) terms.size();
    }

    private static int monthIndex(String yearMonth) {
        String ym = yearMonth.length() == 4 ? yearMonth + "-01" : yearMonth.substring(0, 7);
        YearMonth y = YearMonth.parse(ym);
        return y.getYear() * 12 + y.getMonthValue() - 1;
    }

    private static String fmt(double years) {
        return years == Math.rint(years) ? String.valueOf((int) years) : String.valueOf(years);
    }
}
