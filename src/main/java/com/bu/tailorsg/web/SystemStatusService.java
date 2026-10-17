package com.bu.tailorsg.web;

import com.bu.tailorsg.config.TailorSgProperties;
import com.bu.tailorsg.master.MasterResumeService;
import com.bu.tailorsg.master.MasterResumeService.StoredVersion;
import com.bu.tailorsg.updates.PendingUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Gathers the facts shown on the dashboard and returned by {@code GET /api/health}.
 * Shared so the page and the endpoint can never disagree.
 */
@Service
public class SystemStatusService {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusService.class);

    private final JdbcTemplate jdbc;
    private final boolean apiKeySet;
    private final boolean demoMode;
    private final TailorSgProperties props;
    private final MasterResumeService masterService;
    private final PendingUpdateService updates;

    public SystemStatusService(JdbcTemplate jdbc, com.bu.tailorsg.llm.AnthropicTransport anthropic,
                               @org.springframework.beans.factory.annotation.Value("${tailorsg.llm.demo-mode:false}") boolean demoMode,
                               TailorSgProperties props,
                               MasterResumeService masterService, PendingUpdateService updates) {
        this.jdbc = jdbc;
        this.apiKeySet = anthropic.isConfigured(); // the real key, even when demo mode answers instead
        this.demoMode = demoMode;
        this.props = props;
        this.masterService = masterService;
        this.updates = updates;
    }

    public SystemStatus current() {
        boolean dbUp;
        Integer masterVersion = null;
        String masterSavedAt = null;
        long pendingUpdates = 0;
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            dbUp = true;
            StoredVersion master = masterService.current().orElse(null);
            if (master != null) {
                masterVersion = master.versionNo();
                masterSavedAt = DisplayFormats.dateTime(master.createdAt());
            }
            pendingUpdates = updates.countProposed();
        } catch (DataAccessException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            dbUp = false;
        }
        return new SystemStatus(dbUp, apiKeySet, props.models().writer(), props.models().extractor(),
                masterVersion, masterSavedAt, pendingUpdates, demoMode);
    }

    /**
     * @param apiKeyConfigured whether ANTHROPIC_API_KEY is set (never the key itself)
     * @param demoMode         model answers come from the local demo transport, not Claude
     * @param masterVersion    current master resume version, or null if none yet
     * @param pendingUpdates   proposed updates waiting for review
     */
    public record SystemStatus(boolean databaseUp, boolean apiKeyConfigured, String writerModel, String extractorModel,
                               Integer masterVersion, String masterSavedAt, long pendingUpdates, boolean demoMode) {}
}
