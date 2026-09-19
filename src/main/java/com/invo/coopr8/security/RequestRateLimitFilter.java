package com.invo.coopr8.security;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Small in-process backstop for anonymous, credential-bearing routes.
 *
 * <p>The edge/WAF must enforce matching distributed limits in production; this
 * prevents one JVM from being an unlimited brute-force or storage-abuse target.
 *
 * <p>Client IP is resolved strictly via {@code request.getRemoteAddr()}, which is
 * populated safely by Spring's framework-level forwarded-headers filter when
 * {@code server.forward-headers-strategy=framework} is enabled. Raw
 * {@code X-Forwarded-For} headers from the client are never trusted directly to prevent
 * spoofing.
 *
 * <p>Expired buckets are evicted periodically to prevent unbounded heap growth.
 */
public final class RequestRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MS = Duration.ofMinutes(10).toMillis();
    private static final int EVICTION_INTERVAL = 100;
    private static final ConcurrentHashMap<String, Bucket> BUCKETS = new ConcurrentHashMap<>();
    private static final AtomicInteger REQUEST_COUNT = new AtomicInteger(0);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        int limit = limitFor(request.getRequestURI());
        if (limit > 0 && !accept(request.getRemoteAddr() + ":" + request.getRequestURI(), limit)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "600");
            response.setContentType("application/json");
            response.getWriter().write("{\"responseCode\":\"429\",\"responseMessage\":\"Too many requests. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static int limitFor(String path) {
        if (path.equals("/api/platform/auth/login")) return 10;
        if (path.equals("/api/auth/login")) return 20;
        if (path.startsWith("/api/otp/")) return 12;
        if (path.startsWith("/api/auth/forgot-password")) return 10;
        if (path.equals("/api/images/upload")) return 8;
        if (path.equals("/api/auth/signup")) return 8;
        return 0;
    }

    private static boolean accept(String key, int limit) {
        long now = System.currentTimeMillis();
        if (REQUEST_COUNT.incrementAndGet() % EVICTION_INTERVAL == 0) {
            evictExpired(now);
        }
        Bucket bucket = BUCKETS.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt > WINDOW_MS) return new Bucket(now);
            current.count.incrementAndGet();
            return current;
        });
        return bucket.count.get() <= limit;
    }

    static void evictExpired(long now) {
        BUCKETS.entrySet().removeIf(entry -> now - entry.getValue().startedAt > WINDOW_MS);
    }

    static int bucketCount() {
        return BUCKETS.size();
    }

    static void clearBuckets() {
        BUCKETS.clear();
        REQUEST_COUNT.set(0);
    }

    private static final class Bucket {
        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger(1);
        private Bucket(long startedAt) { this.startedAt = startedAt; }
    }
}
