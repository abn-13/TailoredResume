package com.bu.tailorsg.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code tailorsg.jobs.*}: how job postings are fetched. API base URLs are configurable so tests can point
 * the adapters at a local server holding real saved responses.
 *
 * @param minTextChars      less text than this means a login wall or blocked page (spec: 500)
 * @param allowPrivateHosts false: a pasted link may never point at localhost or a private network
 */
@ConfigurationProperties(prefix = "tailorsg.jobs")
public record JobsProperties(Duration timeout, String userAgent, int minTextChars, String mcfApiBase,
                             String greenhouseApiBase, String leverApiBase, String leverEuApiBase,
                             boolean allowPrivateHosts) {}
