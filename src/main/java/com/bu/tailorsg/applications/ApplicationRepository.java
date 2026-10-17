package com.bu.tailorsg.applications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    List<Application> findAllByOrderByUpdatedAtDescIdDesc();
}
