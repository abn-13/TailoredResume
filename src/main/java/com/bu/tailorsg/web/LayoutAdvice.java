package com.bu.tailorsg.web;

import com.bu.tailorsg.llm.LlmUsageMeter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Adds model attributes every page layout needs (currently the footer's cost estimate).
 *
 * <p>{@code @ModelAttribute} methods in a {@code @ControllerAdvice} run before each handler in the
 * targeted controllers, so individual page methods don't have to remember to add them. Targeting
 * {@code @Controller} covers every page controller; JSON controllers match too but simply ignore it.
 */
@ControllerAdvice(annotations = Controller.class)
public class LayoutAdvice {

    private final LlmUsageMeter meter;
    private final boolean demoMode;

    public LayoutAdvice(LlmUsageMeter meter,
                        @org.springframework.beans.factory.annotation.Value("${tailorsg.llm.demo-mode:false}") boolean demoMode) {
        this.meter = meter;
        this.demoMode = demoMode;
    }

    /** Shows the DEMO MODE banner on every page (model answers are generated locally, not by Claude). */
    @ModelAttribute("demoMode")
    public boolean demoMode() {
        return demoMode;
    }

    @ModelAttribute("llmUsage")
    public LlmUsageMeter.Snapshot llmUsage() {
        return meter.snapshot();
    }
}
