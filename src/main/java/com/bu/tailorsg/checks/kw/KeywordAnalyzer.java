package com.bu.tailorsg.checks.kw;

import com.bu.tailorsg.checks.CheckReport.Status;
import com.bu.tailorsg.checks.CheckThresholds;
import com.bu.tailorsg.checks.MasterTexts;
import com.bu.tailorsg.checks.kw.KeywordAnalysis.Match;
import com.bu.tailorsg.checks.kw.KeywordAnalysis.TermResult;
import com.bu.tailorsg.checks.kw.KeywordMatcher.Term;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.jobs.JobSpec.JdSkill;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResume.Highlight;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out which of a job's skills a resume covers, and for each missing one whether the master has
 * evidence for it (spec 6C). Deterministic: dictionary, aliases and regexes only.
 */
@Component
public class KeywordAnalyzer {

    private final KeywordMatcher matcher;
    private final SkillDictionary dictionary;
    private volatile List<Term> dictionaryTerms;

    public KeywordAnalyzer(SkillDictionary dictionary) {
        this.dictionary = dictionary;
        this.matcher = new KeywordMatcher(dictionary);
    }

    public KeywordMatcher matcher() {
        return matcher;
    }

    public SkillDictionary dictionary() {
        return dictionary;
    }

    /** Every dictionary entry as a matchable term, built once (KW-02, TRUTH-03, TRUTH-04). */
    public List<Term> dictionaryTerms() {
        if (dictionaryTerms == null) {
            dictionaryTerms = dictionary.entries().stream().map(matcher::term).toList();
        }
        return dictionaryTerms;
    }

    /**
     * @param resumeText the resume being scored (rendered text)
     * @param master     where to look for evidence of missing terms
     */
    public KeywordAnalysis analyse(JobSpec job, String resumeText, MasterResume master, CheckThresholds.Kw kw) {
        List<TermResult> required = results(job.requiredSkills(), true, resumeText, master);
        List<TermResult> preferred = results(job.preferredSkills(), false, resumeText, master);
        double reqCov = coverage(required);
        double prefCov = coverage(preferred);
        int score = (int) Math.round(100 * (0.75 * reqCov + 0.25 * prefCov));
        Status status = Status.FAIL;
        if (reqCov >= kw.requiredPass()) {
            status = Status.PASS;
        } else if (reqCov >= kw.requiredWarn()) {
            status = Status.WARN;
        }
        return new KeywordAnalysis(required, preferred, reqCov, prefCov, score, status);
    }

    private List<TermResult> results(List<JdSkill> skills, boolean required, String resumeText, MasterResume master) {
        List<TermResult> results = new ArrayList<>();
        for (JdSkill skill : skills) {
            if (skill.term() == null || skill.term().isBlank()) {
                continue;
            }
            Term term = matcher.term(skill);
            if (term.foundIn(resumeText)) {
                results.add(new TermResult(skill.term(), required, Match.MATCHED, null, null, null));
            } else {
                results.add(evidence(term, skill.term(), required, master));
            }
        }
        return results;
    }

    /** Looks for the term in the master: a highlight first (best evidence), then the skills list. */
    private static TermResult evidence(Term term, String label, boolean required, MasterResume master) {
        for (Owned owned : highlightsWithOwners(master)) {
            if (term.foundIn(MasterTexts.of(owned.highlight()))) {
                Highlight h = owned.highlight();
                return new TermResult(label, required, Match.EVIDENCED_NOT_USED, h.id(), h.text(), owned.itemId());
            }
        }
        for (var s : master.skills()) {
            if (term.foundIn(s.name())) {
                return new TermResult(label, required, Match.EVIDENCED_NOT_USED, s.id(), "Listed in your skills: " + s.name(), null);
            }
        }
        if (term.foundIn(MasterTexts.all(master))) {
            return new TermResult(label, required, Match.EVIDENCED_NOT_USED, null, "Mentioned in your master resume", null);
        }
        return new TermResult(label, required, Match.GAP, null, null, null);
    }

    private record Owned(String itemId, Highlight highlight) {}

    /** Work first, then projects, then volunteering: the order a recruiter weighs them. */
    private static List<Owned> highlightsWithOwners(MasterResume master) {
        List<Owned> all = new ArrayList<>();
        master.work().forEach(w -> w.highlights().forEach(h -> all.add(new Owned(w.id(), h))));
        master.projects().forEach(p -> p.highlights().forEach(h -> all.add(new Owned(p.id(), h))));
        master.volunteer().forEach(v -> v.highlights().forEach(h -> all.add(new Owned(v.id(), h))));
        return all;
    }

    /** No terms means nothing is missing: coverage 1. */
    private static double coverage(List<TermResult> results) {
        if (results.isEmpty()) {
            return 1.0;
        }
        return results.stream().filter(r -> r.match() == Match.MATCHED).count() / (double) results.size();
    }
}
