package com.cropdoc.app.ui.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Typed result wrapper representing Loading, Success, or Error outcomes without throwing
 * unchecked exceptions across layer boundaries.
 *
 * @param <T> payload type
 */
public final class Result<T> {

    public enum Status {
        LOADING,
        SUCCESS,
        ERROR
    }

    @NonNull
    private final Status status;

    @Nullable
    private final T data;

    @Nullable
    private final String errorMessage;

    private Result(@NonNull Status status, @Nullable T data, @Nullable String errorMessage) {
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.data = data;
        this.errorMessage = errorMessage;
    }

    @NonNull
    public static <T> Result<T> loading() {
        return new Result<>(Status.LOADING, null, null);
    }

    @NonNull
    public static <T> Result<T> success(@NonNull T data) {
        return new Result<>(Status.SUCCESS, Objects.requireNonNull(data), null);
    }

    @NonNull
    public static <T> Result<T> error(@NonNull String message) {
        return new Result<>(Status.ERROR, null, Objects.requireNonNull(message));
    }

    @NonNull
    public Status getStatus() {
        return status;
    }

    @Nullable
    public T getData() {
        return data;
    }

    @Nullable
    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS && data != null;
    }
}
