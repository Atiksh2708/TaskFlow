
package com.atiksh.taskFlow.ratelimit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@ConditionalOnProperty(
        prefix = "taskflow.rate-limit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ClientIdentityResolver identityResolver;

    public RateLimitFilter(
            RateLimitService rateLimitService,
            ClientIdentityResolver identityResolver
    ) {
        this.rateLimitService = rateLimitService;
        this.identityResolver = identityResolver;
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

        String clientId = identityResolver.resolveClientId(request);

        RateLimitResult result;

        try {
            result = rateLimitService.checkLimit(clientId);
        } catch (
                RedisConnectionFailureException
                | RedisSystemException ex
        ) {
            // Fail closed: don't allow requests when Redis cannot
            // evaluate the rate limit.
            response.setStatus(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE
            );
            response.setHeader("Retry-After", "5");
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":503,\"error\":\"Service Unavailable\","
                            + "\"message\":\"Rate limiting service unavailable\"}"
            );
            return;
        }

        response.setHeader(
                "X-RateLimit-Remaining",
                String.valueOf(result.remainingTokens())
        );

        if (!result.allowed()) {
            response.setStatus(429);;
            response.setHeader(
                    "Retry-After",
                    String.valueOf(
                            Math.max(1, result.retryAfterSeconds())
                    )
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
