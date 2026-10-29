package com.cropdoc.app.ml;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import java.io.Closeable;

/**
 * Abstraction over on-device leaf disease classification to decouple Domain/UI layers from LiteRT.
 * Returns a strongly-typed ClassificationOutcome to prevent false diagnoses.
 */
public interface LeafClassifier extends Closeable {

    @NonNull
    ClassificationOutcome classify(@NonNull Bitmap leafBitmap);

    @NonNull
    String getModelVersion();

    @Override
    void close();
}
