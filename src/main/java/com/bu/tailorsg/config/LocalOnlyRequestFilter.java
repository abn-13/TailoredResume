package com.bu.tailorsg.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/**
 * Keeps other websites open in your browser from driving this app.
 *
 * <p>The server only listens on 127.0.0.1, but any web page can still make your browser send
 * requests to localhost. Without this filter a hostile page could trigger paid LLM calls or,
 * from Phase 1 on, change the master resume. Two cheap checks avoid adding Spring Security:
 * <ol>
 *   <li><b>Host check (DNS rebinding):</b> an attacker can point their own domain at 127.0.0.1,
 *       but the browser still sends their domain in the Host header, so we reject it.</li>
 *   <li><b>Cross-site writes (CSRF):</b> for non-GET requests, browsers send {@code Origin} and
 *       {@code Sec-Fetch-Site}; we reject anything not from this app. curl and tests send neither
 *       header and are allowed.</li>
 * </ol>
 */
@Component
public class LocalOnlyRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalOnlyRequestFilter.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    /** "none" = typed in the address bar or a bookmark; "same-origin" = our own pages. */
    private static final Set<String> ALLOWED_FETCH_SITES = Set.of("same-origin", "none");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!LOCAL_HOSTS.contains(request.getServerName())) {
            reject(response, "Host '" + request.getServerName() + "' is not allowed");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod())) {
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if (fetchSite != null && !ALLOWED_FETCH_SITES.contains(fetchSite)) {
                reject(response, "Cross-site " + request.getMethod() + " blocked (Sec-Fetch-Site: " + fetchSite + ")");
                return;
            }
            String origin = request.getHeader("Origin");
            if (origin != null && !isLocalOrigin(origin)) {
                reject(response, "Cross-origin " + request.getMethod() + " blocked (Origin: " + origin + ")");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isLocalOrigin(String origin) {
        try {
            String host = URI.create(origin).getHost(); // null for the literal "null" origin
            return host != null && LOCAL_HOSTS.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response, String reason) throws IOException {
        log.warn("Rejected request: {}", reason);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("Forbidden: " + reason);
    }
}
