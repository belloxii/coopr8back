package com.invo.coopr8.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Result of scanning a hardcopy membership form with Gemini vision.
 *
 * {@code fields} is keyed by the canonical column names the frontend already
 * uses (firstName, lastName, gender, paymentType, savingPlan, ...) so the
 * single-user form can prefill directly and the batch tab can drop each entry
 * straight into an import row. {@code passport} is the Cloudinary URL of the
 * passport photograph cropped out of the form, or null when none was detected.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FormScanResponse {

    private Map<String, String> fields;

    private String passport;

    private boolean passportDetected;

    /** Human-readable note when something was partial (e.g. crop/upload failed). */
    private String note;
}
