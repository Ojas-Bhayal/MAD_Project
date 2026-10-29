package com.cropdoc.app.ml.research;

import androidx.annotation.NonNull;
import java.nio.ByteBuffer;

/**
 * Isolated model execution contract for research-only mobile candidate models.
 */
public interface ResearchModelExecutor {

    /**
     * Executes inference on the prepared input buffer and returns raw logits.
     *
     * @param inputBuffer input tensor buffer
     * @return raw logit array
     * @throws Exception if inference fails or interpreter encounters an error
     */
    @NonNull
    float[] execute(@NonNull ByteBuffer inputBuffer) throws Exception;

    /**
     * Checks whether the underlying model interpreter is loaded and operational.
     *
     * @return true if model is loaded and ready
     */
    boolean isAvailable();

    /**
     * Releases underlying interpreter resources.
     */
    void close();
}
