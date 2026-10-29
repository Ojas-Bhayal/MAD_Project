package com.cropdoc.app.ml.policy;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;

/**
 * Contract for image-driven botanical crop domain classification.
 * Evaluates whether an input leaf belongs to a supported agricultural domain (Rice, Groundnut)
 * or an unsupported crop (Cotton, Cassava, Maize, Tomato, etc.).
 */
public interface CropDomainGate {

    enum GateState {
        NOT_PRESENT,
        UNVALIDATED,
        VALIDATED
    }

    @NonNull
    GateState getState();

    @NonNull
    CropDomainResult evaluate(@NonNull Bitmap bitmap, @Nullable ByteBuffer inputBuffer);
}
