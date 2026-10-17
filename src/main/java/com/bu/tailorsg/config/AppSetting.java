package com.bu.tailorsg.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/** A setting changed in the UI (Phase 9), stored as text under a key; application.yml holds the defaults. */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(length = 60)
    private String settingKey;

    @Lob
    @Column(nullable = false)
    private String settingValue;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AppSetting() {}

    public AppSetting(String key, String value, Instant updatedAt) {
        this.settingKey = key;
        this.settingValue = value;
        this.updatedAt = updatedAt;
    }

    public String getSettingKey() { return settingKey; }
    public String getSettingValue() { return settingValue; }
    public Instant getUpdatedAt() { return updatedAt; }
}
