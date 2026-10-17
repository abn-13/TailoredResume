package com.bu.tailorsg.updates;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingUpdateRepository extends JpaRepository<PendingUpdate, Long> {

    List<PendingUpdate> findAllByOrderByCreatedAtDescIdDesc();

    List<PendingUpdate> findByStatusOrderByCreatedAtDescIdDesc(PendingUpdate.Status status);

    long countByStatus(PendingUpdate.Status status);
}
