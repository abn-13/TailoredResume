package com.bu.tailorsg.master;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IdCounterRepository extends JpaRepository<IdCounter, String> {
}
