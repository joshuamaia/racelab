package dev.racelab.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Correlation id propagation (X-Correlation-Id, MDC), payload size limit and a fixed-window rate limit on
 * mutating requests per client address.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGuardFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private final int maxBodyBytes;
    private final int mutationsPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RequestGuardFilter(@Value("${racelab.limits.max-body-bytes:65536}") int maxBodyBytes,
                              @Value("${racelab.limits.mutations-per-minute:120}") int mutationsPerMinute) {
        this.maxBodyBytes = maxBodyBytes;
        this.mutationsPerMinute = mutationsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(CORRELATION_HEADER);
        String correlationId = incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        response.setHeader(CORRELATION_HEADER, correlationId);
        try {
            if (request.getContentLengthLong() > maxBodyBytes) {
                reject(response, 413, "PAYLOAD_TOO_LARGE", "Request body exceeds " + maxBodyBytes + " bytes", correlationId,
                        request.getRequestURI());
                return;
            }
            boolean mutation = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())
                    && !"OPTIONS".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/");
            if (mutation && !allow(clientKey(request))) {
                response.setHeader("Retry-After", "60");
                reject(response, 429, "RATE_LIMITED", "Too many requests; try again in a minute", correlationId,
                        request.getRequestURI());
                return;
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }

    private boolean allow(String key) {
        long minute = Instant.now().getEpochSecond() / 60;
        Window w = windows.compute(key, (k, old) -> old == null || old.minute != minute ? new Window(minute) : old);
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute != minute);
        }
        return w.count.incrementAndGet() <= mutationsPerMinute;
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    private static void reject(HttpServletResponse response, int status, String code, String message, String correlationId,
                               String path) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"timestamp\":\"" + Instant.now() + "\",\"status\":" + status + ",\"error\":\""
                + (status == 429 ? "Too Many Requests" : "Payload Too Large") + "\",\"code\":\"" + code
                + "\",\"message\":\"" + message + "\",\"details\":[],\"correlationId\":\"" + correlationId
                + "\",\"path\":\"" + path.replace("\"", "") + "\"}");
    }

    private static final class Window {
        final long minute;
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

        Window(long minute) {
            this.minute = minute;
        }
    }
}
