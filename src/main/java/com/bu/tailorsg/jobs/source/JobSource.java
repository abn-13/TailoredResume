package com.bu.tailorsg.jobs.source;

import java.net.URI;
import java.util.Optional;

/**
 * One way of getting a job posting from a URL (spec 3: "JobSourceAdapter chain, first match wins").
 * Return empty to let the next source try; throw {@link BlockedSiteException} to stop the chain.
 */
public interface JobSource {

    String name();

    boolean supports(URI uri);

    Optional<FetchedJob> fetch(URI uri);
}
