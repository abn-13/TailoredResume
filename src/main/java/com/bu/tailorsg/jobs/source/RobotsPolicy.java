package com.bu.tailorsg.jobs.source;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal robots.txt support for generic page fetching (spec 3: "Respect robots.txt"): the rules for
 * {@code User-agent: *} (or TailorSG), longest matching Allow/Disallow prefix wins. If robots.txt cannot be
 * read, fetching is allowed, as is conventional.
 */
@Component
public class RobotsPolicy {

    private final HttpFetcher fetcher;
    private final Map<String, List<Rule>> cache = new ConcurrentHashMap<>();

    public RobotsPolicy(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    record Rule(boolean allow, String prefix) {}

    public boolean allows(URI uri) {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        List<Rule> rules = cache.computeIfAbsent(origin, this::load);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        Rule best = null;
        for (Rule r : rules) {
            if (!r.prefix().isEmpty() && path.startsWith(r.prefix())
                    && (best == null || r.prefix().length() > best.prefix().length()
                    || (r.prefix().length() == best.prefix().length() && r.allow()))) {
                best = r;
            }
        }
        return best == null || best.allow();
    }

    private List<Rule> load(String origin) {
        try {
            HttpFetcher.Response response = fetcher.get(URI.create(origin + "/robots.txt"), "text/plain");
            return response.ok() ? parse(response.body()) : List.of();
        } catch (IOException | IllegalArgumentException e) {
            return List.of();
        }
    }

    static List<Rule> parse(String robots) {
        List<Rule> rules = new ArrayList<>();
        boolean applies = false;
        boolean inAgents = false;
        for (String raw : robots.split("\\R")) {
            String line = raw.replaceFirst("#.*", "").strip();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).strip();
            if (key.equals("user-agent")) {
                if (!inAgents) {
                    applies = false; // a new group starts
                }
                inAgents = true;
                applies |= value.equals("*") || value.equalsIgnoreCase("TailorSG");
            } else {
                inAgents = false;
                if (applies && (key.equals("disallow") || key.equals("allow"))) {
                    rules.add(new Rule(key.equals("allow"), value));
                }
            }
        }
        return rules;
    }
}
