package com.bu.tailorsg.export;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExportFileRepository extends JpaRepository<ExportFile, Long> {

    List<ExportFile> findByApplicationIdOrderByCreatedAtDescIdDesc(long applicationId);
}
