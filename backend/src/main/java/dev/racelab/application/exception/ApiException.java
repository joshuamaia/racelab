package dev.racelab.application.exception;

import java.util.List;

/** Application exception mapped to a standard error payload by the web adapter. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<String> details;

    public ApiException(int status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(int status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<String> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "NOT_FOUND", what + " not found");
    }

    public static ApiException invalidState(String message) {
        return new ApiException(409, "INVALID_STATE", message);
    }

    public static ApiException validation(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }

    public static ApiException validation(String message, List<String> details) {
        return new ApiException(400, "VALIDATION_FAILED", message, details);
    }
}
