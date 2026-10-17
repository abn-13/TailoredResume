package com.bu.tailorsg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for TailorSG, a local single-user web app that tailors a master resume
 * to Singapore job descriptions and judges the result with deterministic checks.
 *
 * <p>{@code @ConfigurationPropertiesScan} finds {@code @ConfigurationProperties} records
 * (see {@link com.bu.tailorsg.config.TailorSgProperties}) without listing them one by one.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TailorSgApplication {

    public static void main(String[] args) {
        SpringApplication.run(TailorSgApplication.class, args);
    }
}
