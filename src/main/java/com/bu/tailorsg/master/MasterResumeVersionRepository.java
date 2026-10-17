package com.bu.tailorsg.master;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data generates the implementation of these methods from their names at startup
 * ("derived queries"), e.g. findTopByOrderByVersionNoDesc becomes
 * {@code select ... order by version_no desc limit 1}.
 */
public interface MasterResumeVersionRepository extends JpaRepository<MasterResumeVersion, Long> {

    /** The current version. */
    Optional<MasterResumeVersion> findTopByOrderByVersionNoDesc();

    Optional<MasterResumeVersion> findByVersionNo(int versionNo);

    /**
     * History list, newest first. Returns a projection (an interface with getters), so Spring Data
     * selects only these columns and never loads the large JSON CLOBs.
     */
    List<VersionSummary> findAllByOrderByVersionNoDesc();

    interface VersionSummary {
        int getVersionNo();

        Instant getCreatedAt();

        String getChangeNote();
    }
}
