package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;

/**
 * Botanical domain categories recognized by the CropDoc crop domain gate.
 */
public enum CropDomain {
    SUPPORTED_RICE("rice"),
    SUPPORTED_GROUNDNUT("groundnut"),
    UNSUPPORTED_CROP("unsupported");

    private final String cropKey;

    CropDomain(String cropKey) {
        this.cropKey = cropKey;
    }

    @NonNull
    public String getCropKey() {
        return cropKey;
    }
}
