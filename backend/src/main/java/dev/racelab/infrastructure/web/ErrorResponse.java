package dev.racelab.infrastructure.web;

import java.time.Instant;
import java.util.List;

/** Standard error payload shared by the backend and the BFF. */
public record ErrorResponse(Instant timestamp, int status, String error, String code, String message,
                            List<String> details, String correlationId, String path) {
}
