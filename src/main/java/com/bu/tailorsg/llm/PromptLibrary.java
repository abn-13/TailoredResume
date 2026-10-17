package com.bu.tailorsg.llm;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads prompt files from {@code classpath:prompts/} at runtime and caches them.
 *
 * <p>Prompts live in resource files, not Java strings, so they can be read, diffed and
 * versioned like documents.
 */
@Component
public class PromptLibrary {

    private final ResourceLoader resourceLoader;
    private final Map<String, PromptTemplate> cache = new ConcurrentHashMap<>();

    public PromptLibrary(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public PromptTemplate get(String id) {
        return cache.computeIfAbsent(id, this::load);
    }

    private PromptTemplate load(String id) {
        Resource resource = resourceLoader.getResource("classpath:prompts/" + id + ".md");
        if (!resource.exists()) {
            throw new IllegalArgumentException("No prompt file at prompts/" + id + ".md");
        }
        try {
            return PromptTemplate.parse(id, resource.getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read prompts/" + id + ".md", e);
        }
    }
}
