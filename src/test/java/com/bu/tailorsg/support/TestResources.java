package com.bu.tailorsg.support;

import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Reads test fixtures from the classpath (src/test/resources), for tests in any package. */
public final class TestResources {

    private TestResources() {}

    /** @param path e.g. "fixtures/jobs/llm-jobspec.json" */
    public static String text(String path) {
        try {
            return new DefaultResourceLoader().getResource("classpath:" + path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
