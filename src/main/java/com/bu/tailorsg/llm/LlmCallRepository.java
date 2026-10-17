package com.bu.tailorsg.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

    @Query("select coalesce(sum(c.costUsd), 0) from LlmCall c")
    BigDecimal totalCost();

    List<LlmCall> findTop20ByOrderByCreatedAtDescIdDesc();
}
