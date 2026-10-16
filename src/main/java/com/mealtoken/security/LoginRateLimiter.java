package com.mealtoken.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Simple in-memory sliding-window limiter for POST /login (10 attempts / 5 min / IP). */
@Component
public class LoginRateLimiter extends OncePerRequestFilter {
    private static final int MAX = 10;
    private static final long WINDOW_SECONDS = 300;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !(req.getMethod().equals("POST") && req.getRequestURI().equals("/login"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = req.getRemoteAddr();
        Deque<Instant> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        Instant now = Instant.now();
        boolean blocked;
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minusSeconds(WINDOW_SECONDS))) q.pollFirst();
            blocked = q.size() >= MAX;
            if (!blocked) q.addLast(now);
        }
        if (blocked) {
            res.sendRedirect("/login?throttled");
            return;
        }
        chain.doFilter(req, res);
    }
}
