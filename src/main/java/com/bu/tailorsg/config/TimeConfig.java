package com.bu.tailorsg.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the {@link Clock} that services use for "now". Injecting a Clock instead of calling
 * {@code Instant.now()} directly lets a test swap in a fixed time if it ever needs to.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
