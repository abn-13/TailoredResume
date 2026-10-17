package com.bu.tailorsg.checks;

import com.bu.tailorsg.config.AppSetting;
import com.bu.tailorsg.config.AppSettingRepository;
import com.bu.tailorsg.json.JsonText;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The thresholds in force: application.yml ({@link CheckThresholds}) unless the Settings page saved an
 * override, which is stored whole (as JSON) in the {@code app_setting} table and validated before use.
 */
@Service
public class ThresholdService {

    static final String KEY = "check-thresholds";

    private final CheckThresholds defaults;
    private final AppSettingRepository settings;
    private final JsonMapper json;
    private final JsonText jsonText;
    private final Clock clock;

    public ThresholdService(CheckThresholds defaults, AppSettingRepository settings, JsonMapper json, JsonText jsonText, Clock clock) {
        this.defaults = defaults;
        this.settings = settings;
        this.json = json;
        this.jsonText = jsonText;
        this.clock = clock;
    }

    /**
     * Read from the database each time (a primary-key lookup, once per check run) rather than cached, so a
     * saved or rolled-back override can never be stale.
     */
    public CheckThresholds current() {
        return override().map(s -> json.readValue(s.getSettingValue(), CheckThresholds.class)).orElse(defaults);
    }

    public CheckThresholds defaults() {
        return defaults;
    }

    public boolean isOverridden() {
        return override().isPresent();
    }

    /** Pretty JSON of the thresholds in force, for the Settings editor. */
    public String currentJson() {
        return jsonText.pretty(json.valueToTree(current()));
    }

    /**
     * Validates and stores a full set of thresholds.
     *
     * @return problems found; empty means saved
     */
    public List<String> save(String text) {
        CheckThresholds parsed;
        try {
            parsed = json.treeToValue(jsonText.parseStrict(text), CheckThresholds.class);
        } catch (JsonText.JsonSyntaxException e) {
            return List.of("Not valid JSON: " + e.describe());
        } catch (JacksonException e) {
            return List.of("Does not match the thresholds format: " + e.getOriginalMessage());
        }
        List<String> problems = validate(parsed);
        if (problems.isEmpty()) {
            settings.save(new AppSetting(KEY, json.writeValueAsString(parsed), clock.instant()));
        }
        return problems;
    }

    public void reset() {
        if (settings.existsById(KEY)) {
            settings.deleteById(KEY);
        }
    }

    private Optional<AppSetting> override() {
        return settings.findById(KEY);
    }

    static List<String> validate(CheckThresholds t) {
        List<String> p = new ArrayList<>();
        if (t.kw() == null || t.lint() == null || t.pages() == null || t.ats() == null || t.cl() == null) {
            p.add("Every section is required: kw, lint, pages, ats, cl.");
            return p;
        }
        between(p, "kw.requiredPass", t.kw().requiredPass(), 0, 1);
        between(p, "kw.requiredWarn", t.kw().requiredWarn(), 0, t.kw().requiredPass());
        positive(p, "kw.maxKeywordRepeats", t.kw().maxKeywordRepeats());
        positive(p, "kw.maxSkillsPerBullet", t.kw().maxSkillsPerBullet());
        positive(p, "lint.minWords", t.lint().minWords());
        if (t.lint().maxWords() <= t.lint().minWords()) {
            p.add("lint.maxWords must be greater than lint.minWords.");
        }
        positive(p, "lint.maxLines", t.lint().maxLines());
        positive(p, "lint.charsPerLine", t.lint().charsPerLine());
        between(p, "lint.experienceMetricShare", t.lint().experienceMetricShare(), 0, 1);
        between(p, "lint.projectMetricShare", t.lint().projectMetricShare(), 0, 1);
        positive(p, "lint.maxSameStartVerb", t.lint().maxSameStartVerb());
        if (t.lint().cliches() == null || t.lint().redundant() == null) {
            p.add("lint.cliches and lint.redundant are required (they may be empty).");
        }
        positive(p, "pages.wordsPerPage", t.pages().wordsPerPage());
        positive(p, "ats.minTextChars", t.ats().minTextChars());
        between(p, "ats.minTextRatio", t.ats().minTextRatio(), 0, 1);
        between(p, "ats.orderWarn", t.ats().orderWarn(), 0, 1);
        between(p, "ats.orderError", t.ats().orderError(), 0, t.ats().orderWarn());
        between(p, "ats.errorBodyPt", t.ats().errorBodyPt(), 6, t.ats().minBodyPt());
        between(p, "ats.parserRecall", t.ats().parserRecall(), 0, 1);
        positive(p, "ats.dateWindowChars", t.ats().dateWindowChars());
        if (t.ats().fonts() == null || t.ats().fonts().isEmpty()) {
            p.add("ats.fonts needs at least one font.");
        }
        positive(p, "cl.minWords", t.cl().minWords());
        if (!(t.cl().minWords() < t.cl().maxWords() && t.cl().maxWords() <= t.cl().hardMaxWords())) {
            p.add("cl needs minWords < maxWords <= hardMaxWords.");
        }
        positive(p, "cl.maxIRun", t.cl().maxIRun());
        if (t.cl().bannedPhrases() == null) {
            p.add("cl.bannedPhrases is required (it may be empty).");
        }
        return p;
    }

    private static void between(List<String> p, String name, double v, double lo, double hi) {
        if (v < lo || v > hi) {
            p.add(name + " must be between " + lo + " and " + hi + " (was " + v + ").");
        }
    }

    private static void positive(List<String> p, String name, int v) {
        if (v <= 0) {
            p.add(name + " must be a positive whole number (was " + v + ").");
        }
    }
}
