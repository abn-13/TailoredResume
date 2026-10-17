package com.bu.tailorsg.applications;

import org.springframework.ui.Model;

/**
 * Adds model attributes for one tab of the application page (fit, cover letter, export), so each feature
 * keeps its own code and the page controller does not grow a dependency on every service.
 */
public interface ApplicationTabContributor {

    void contribute(Application application, String activeTab, Model model);
}
