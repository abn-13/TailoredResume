package com.bu.tailorsg.jobs.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Tries each {@link JobSource} in {@code @Order} (MyCareersFuture, Greenhouse, Lever, then any web page);
 * the first that returns a posting wins (spec 3). Spring injects the sources as a list already sorted by
 * their {@code @Order}.
 */
@Component
public class JobSourceChain {

    private static final Logger log = LoggerFactory.getLogger(JobSourceChain.class);

    private final List<JobSource> sources;

    public JobSourceChain(List<JobSource> sources) {
        this.sources = sources;
    }

    /**
     * @throws BlockedSiteException  when the site must not be fetched (show the paste/screenshot advice)
     * @throws IllegalStateException when nothing could read the page
     */
    public FetchedJob fetch(URI uri) {
        for (JobSource source : sources) {
            if (source.supports(uri)) {
                Optional<FetchedJob> job = source.fetch(uri);
                if (job.isPresent()) {
                    log.info("Fetched {} with {}", uri.getHost(), source.name());
                    return job.get();
                }
            }
        }
        throw new IllegalStateException("Could not read a job posting at that link. Paste the text instead.");
    }
}
