package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;
import com.cropdoc.app.ml.LeafGate;
import java.util.Objects;

/**
 * Immutable encapsulation of leaf gate state and decision for policy evaluation.
 */
public final class LeafGateValidationStatus {

    private final boolean gatePresent;
    private final boolean gateCertified;
    @NonNull private final LeafGate.GateDecision decision;
    @NonNull private final String explanation;

    public LeafGateValidationStatus(
            boolean gatePresent,
            boolean gateCertified,
            @NonNull LeafGate.GateDecision decision,
            @NonNull String explanation) {
        this.gatePresent = gatePresent;
        this.gateCertified = gateCertified;
        this.decision = Objects.requireNonNull(decision, "decision");
        this.explanation = Objects.requireNonNull(explanation, "explanation");
    }

    @NonNull
    public static LeafGateValidationStatus validatedAndAccepted(@NonNull String explanation) {
        return new LeafGateValidationStatus(true, true, LeafGate.GateDecision.ACCEPT, explanation);
    }

    @NonNull
    public static LeafGateValidationStatus rejectedNonLeaf(@NonNull String explanation) {
        return new LeafGateValidationStatus(true, true, LeafGate.GateDecision.REJECT, explanation);
    }

    @NonNull
    public static LeafGateValidationStatus unvalidated(@NonNull String explanation) {
        return new LeafGateValidationStatus(true, false, LeafGate.GateDecision.UNCERTAIN, explanation);
    }

    @NonNull
    public static LeafGateValidationStatus notPresent() {
        return new LeafGateValidationStatus(false, false, LeafGate.GateDecision.UNCERTAIN, "Leaf gate not present");
    }

    @NonNull
    public static LeafGateValidationStatus fromGate(
            @NonNull LeafGate leafGate,
            @NonNull LeafGate.GateResult result) {
        boolean present = leafGate.getState() != LeafGate.GateState.NOT_PRESENT;
        boolean certified = leafGate.getState() == LeafGate.GateState.VALIDATED;
        return new LeafGateValidationStatus(present, certified, result.getDecision(), result.getExplanation());
    }

    public boolean isGatePresent() {
        return gatePresent;
    }

    public boolean isGateCertified() {
        return gateCertified;
    }

    @NonNull
    public LeafGate.GateDecision getDecision() {
        return decision;
    }

    @NonNull
    public String getExplanation() {
        return explanation;
    }

    public boolean isPassConfirmed() {
        return gatePresent && gateCertified && decision == LeafGate.GateDecision.ACCEPT;
    }
}
