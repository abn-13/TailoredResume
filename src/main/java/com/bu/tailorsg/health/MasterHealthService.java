package com.bu.tailorsg.health;

import com.bu.tailorsg.checks.Bullet;
import com.bu.tailorsg.checks.Category;
import com.bu.tailorsg.checks.CheckContext;
import com.bu.tailorsg.checks.CheckContexts;
import com.bu.tailorsg.checks.CheckEngine;
import com.bu.tailorsg.checks.CheckMode;
import com.bu.tailorsg.checks.CheckReport;
import com.bu.tailorsg.checks.Finding;
import com.bu.tailorsg.checks.ThresholdService;
import com.bu.tailorsg.jobs.JobSpec;
import com.bu.tailorsg.master.MasterResume;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.render.ResumeDocument;
import com.bu.tailorsg.render.ResumeRenderer;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Master health (spec 3: GET /api/master/health): bullet lint and Singapore checks on the whole master, with
 * no job involved; plus a keyword check of the master against hand-entered or saved job terms.
 */
@Service
public class MasterHealthService {

    private final MasterResumeService master;
    private final ResumeRenderer renderer;
    private final CheckEngine engine;
    private final ThresholdService thresholds;

    public MasterHealthService(MasterResumeService master, ResumeRenderer renderer, CheckEngine engine,
                               ThresholdService thresholds) {
        this.master = master;
        this.renderer = renderer;
        this.engine = engine;
        this.thresholds = thresholds;
    }

    /** @param unevidencedSkills names of skills with no evidence (spec 2.1: flagged in the health view) */
    public record Health(CheckReport report, List<String> unevidencedSkills, int bullets) {}

    public Optional<Health> health() {
        return master.currentResume().map(m -> {
            CheckContext ctx = context(m).build();
            CheckReport report = engine.run(ctx, EnumSet.of(Category.SG, Category.LINT));
            List<String> unevidenced = m.skills().stream().filter(s -> s.evidenceIds().isEmpty())
                    .map(s -> s.name() + " (" + s.id() + ")").toList();
            return new Health(report, unevidenced, ctx.bullets().size());
        });
    }

    /** KW against the whole master: "how much of this job can my master cover at all?" */
    public Optional<CheckReport> keywords(JobSpec job) {
        return master.currentResume().map(m -> engine.run(context(m).job(job).build(), EnumSet.of(Category.KW)));
    }

    /**
     * LINT rules for one draft bullet (live preview on the highlight form). Resume-level findings, such as
     * metric shares, are dropped: they make no sense for a single bullet.
     */
    public List<Finding> lintOne(String text, Bullet.Kind kind, boolean currentRole) {
        MasterResume m = master.currentResume().orElse(null);
        if (m == null || text == null || text.isBlank()) {
            return List.of();
        }
        Bullet bullet = new Bullet("draft", "draft", kind, "draft", 0, currentRole, text.strip(), List.of());
        CheckContext ctx = CheckContext.builder(CheckMode.MASTER, m, thresholds.current())
                .text(text).bullets(List.of(bullet)).build();
        return engine.run(ctx, EnumSet.of(Category.LINT)).all().stream()
                .filter(f -> "draft".equals(f.location())).toList();
    }

    private CheckContext.Builder context(MasterResume m) {
        ResumeDocument doc = renderer.renderMaster(m);
        boolean showAuth = m.basics().workAuthorization() != null && m.basics().workAuthorization().showOnResumeDefault();
        return CheckContexts.fromDocument(CheckMode.MASTER, m, doc, thresholds.current(), 2, showAuth);
    }
}
