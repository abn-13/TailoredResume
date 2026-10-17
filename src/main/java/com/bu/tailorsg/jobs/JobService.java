package com.bu.tailorsg.jobs;

import com.bu.tailorsg.jobs.JobPosting.SourceType;
import com.bu.tailorsg.jobs.source.FetchedJob;
import com.bu.tailorsg.jobs.source.JobSourceChain;
import com.bu.tailorsg.jobs.source.UrlGuard;
import com.bu.tailorsg.llm.ClaudeClient;
import com.bu.tailorsg.llm.ClaudeClient.LlmResult;
import com.bu.tailorsg.llm.LlmTransport.ImageBlock;
import com.bu.tailorsg.llm.LlmTransport.TextBlock;
import com.bu.tailorsg.llm.LlmTransport.UserBlock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Job postings in, {@link JobSpec}s out (spec 3, "Job descriptions"): from a link (adapter chain), from
 * screenshots (vision), or from pasted text. Every route ends with the JD extraction prompt (spec 5.1).
 */
@Service
public class JobService {

    static final String PROMPT = "jd-extract";
    static final int MAX_TEXT_CHARS = 60_000;

    private final JobPostingRepository repository;
    private final JobSourceChain sources;
    private final UrlGuard urlGuard;
    private final ClaudeClient claude;
    private final JsonMapper json;
    private final Clock clock;

    public JobService(JobPostingRepository repository, JobSourceChain sources, UrlGuard urlGuard, ClaudeClient claude,
                      JsonMapper json, Clock clock) {
        this.repository = repository;
        this.sources = sources;
        this.urlGuard = urlGuard;
        this.claude = claude;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public JobPosting fromText(String text) {
        String trimmed = requireText(text);
        JobSpec spec = extract(List.of(TextBlock.of("<job_posting>\n" + trimmed + "\n</job_posting>")), null);
        return save(SourceType.PASTE, null, null, trimmed, spec, "Pasted text");
    }

    /**
     * @throws com.bu.tailorsg.jobs.source.BlockedSiteException for LinkedIn, logins, 401/403/429, robots.txt
     * @throws IllegalArgumentException for a bad link
     */
    @Transactional
    public JobPosting fromUrl(String link) {
        URI uri = urlGuard.check(link);
        FetchedJob fetched = sources.fetch(uri);
        String text = fetched.text().length() > MAX_TEXT_CHARS ? fetched.text().substring(0, MAX_TEXT_CHARS) : fetched.text();
        List<UserBlock> blocks = new ArrayList<>();
        blocks.add(TextBlock.of("<job_posting>\n" + text + "\n</job_posting>"));
        if (fetched.title() != null || fetched.company() != null) {
            blocks.add(TextBlock.of("Structured fields from " + fetched.source() + ": title \"" + nz(fetched.title())
                    + "\", company \"" + nz(fetched.company()) + "\"."));
        }
        JobSpec spec = extract(blocks, fetched);
        return save(SourceType.URL, uri.toString(), uri.getHost(), text, spec, fetched.source());
    }

    /** @param images already validated and downscaled, in page order */
    @Transactional
    public JobPosting fromImages(List<ImageBlock> images) {
        if (images.isEmpty() || images.size() > ScreenshotInput.MAX_IMAGES) {
            throw new IllegalArgumentException("Upload 1 to " + ScreenshotInput.MAX_IMAGES + " screenshots.");
        }
        List<UserBlock> blocks = new ArrayList<>(images);
        blocks.add(TextBlock.of("These images are consecutive parts of one job posting."));
        JobSpec spec = extract(blocks, null);
        return save(SourceType.SCREENSHOT, null, null, null, spec, images.size() + " screenshot" + (images.size() == 1 ? "" : "s"));
    }

    @Transactional(readOnly = true)
    public JobPosting get(long id) {
        return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<JobPosting> list() {
        return repository.findAllByOrderByCreatedAtDescIdDesc();
    }

    public JobSpec specOf(JobPosting posting) {
        return json.readValue(posting.getExtracted(), JobSpec.class);
    }

    /** User corrections (spec 3: PUT /api/jobs/{id}). */
    @Transactional
    public JobPosting update(long id, JobSpec spec) {
        JobPosting posting = get(id);
        posting.updateSpec(json.writeValueAsString(spec), spec.title(), spec.company(), clock.instant());
        return posting;
    }

    private JobSpec extract(List<UserBlock> blocks, FetchedJob source) {
        LlmResult result = claude.call(PROMPT, blocks);
        return JobSpecMapper.map(result.json(), source);
    }

    private JobPosting save(SourceType type, String url, String host, String rawText, JobSpec spec, String note) {
        return repository.save(new JobPosting(type, url, host, rawText, json.writeValueAsString(spec), spec.title(),
                spec.company(), note, clock.instant()));
    }

    private static String requireText(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.length() < 100) {
            throw new IllegalArgumentException("Paste the whole job description (at least a few sentences).");
        }
        return trimmed.length() > MAX_TEXT_CHARS ? trimmed.substring(0, MAX_TEXT_CHARS) : trimmed;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
