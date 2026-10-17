package com.bu.tailorsg.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Highest number ever issued for an ID prefix: "work" for work-N, "work-1.h" for work-1.hN.
 *
 * <p>It only ever goes up. That is what makes IDs "never reused": after work-2 is deleted the counter
 * still says 2, so the next job becomes work-3, and anyone typing work-2 by hand is told it is retired.
 */
@Entity
@Table(name = "id_counter")
public class IdCounter {

    @Id
    @Column(length = 64)
    private String prefix;

    @Column(nullable = false)
    private long lastValue;

    protected IdCounter() {}

    public IdCounter(String prefix, long lastValue) {
        this.prefix = prefix;
        this.lastValue = lastValue;
    }

    public String getPrefix() {
        return prefix;
    }

    public long getLastValue() {
        return lastValue;
    }

    /** Raises the counter; never lowers it. JPA writes the change on commit (dirty checking). */
    public void raiseTo(long value) {
        lastValue = Math.max(lastValue, value);
    }
}
