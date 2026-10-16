package com.mealtoken.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Small in-memory sliding-window limiter for sensitive endpoints (QR redemption, password reset). */
@Component
public class RequestRateLimiter {
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /** @return true if the call is allowed. */
    public boolean tryAcquire(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > windowMillis) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
        }
        if (hits.size() > 50_000) hits.entrySet().removeIf(e -> { synchronized (e.getValue()) { return e.getValue().isEmpty(); } });
        return true;
    }
}
