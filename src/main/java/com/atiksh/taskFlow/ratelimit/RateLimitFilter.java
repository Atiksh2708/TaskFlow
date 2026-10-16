package com.atiksh.taskFlow.ratelimit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;

    public RateLimitFilter(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // Keep operational health checks accessible.
        if (request.getRequestURI().startsWith("/actuator/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Temporary client identification; we'll improve this next.
        String clientId = request.getRemoteAddr();

        RateLimitResult result = rateLimitService.checkLimit(clientId);

        response.setHeader(
                "X-RateLimit-Remaining",
                String.valueOf(result.remainingTokens())
        );

        if (!result.allowed()) {
            response.setStatus(429);
            response.setHeader(
                    "Retry-After",
                    String.valueOf(Math.max(1, result.retryAfterSeconds()))
            );
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\","
                            + "\"message\":\"Rate limit exceeded\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}