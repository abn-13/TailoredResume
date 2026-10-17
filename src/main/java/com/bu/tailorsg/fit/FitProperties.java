package com.bu.tailorsg.fit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * {@code tailorsg.fit.*}: inputs to the job-fit facts that change over time and must be checked against the
 * official source (MOM), not hard-coded.
 *
 * @param epThresholds Employment Pass minimum qualifying salaries (monthly, SGD, youngest applicants; older
 *                     applicants need more), each with the date it applies to new applications from
 * @param epSourceUrl  where to check the current figures
 */
@ConfigurationProperties(prefix = "tailorsg.fit")
public record FitProperties(List<EpThreshold> epThresholds, String epSourceUrl) {

    public FitProperties {
        epThresholds = epThresholds == null ? List.of() : List.copyOf(epThresholds);
    }

    public record EpThreshold(LocalDate from, int general, int financialServices) {}

    /** The threshold in force for a new application on {@code date}. */
    public Optional<EpThreshold> epThresholdOn(LocalDate date) {
        return epThresholds.stream().filter(t -> !t.from().isAfter(date)).max(Comparator.comparing(EpThreshold::from));
    }
}
