package com.atiksh.taskFlow.ratelimit;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterTests {

    private RateLimitService rateLimitService;
    private ClientIdentityResolver identityResolver;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        identityResolver = mock(ClientIdentityResolver.class);
        filter = new RateLimitFilter(rateLimitService, identityResolver);
    }

    @Test
    void allowedRequestShouldContinueAndIncludeRemainingTokens()
            throws Exception {

        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/jobs");

        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(identityResolver.resolveClientId(request))
                .thenReturn("ip:127.0.0.1");

        when(rateLimitService.checkLimit("ip:127.0.0.1"))
                .thenReturn(new RateLimitResult(true, 9, 0));

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("9",
                response.getHeader("X-RateLimit-Remaining"));

        verify(chain).doFilter(request, response);
    }

    @Test
    void rateLimitedRequestShouldReturn429WithoutContinuing()
            throws Exception {

        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/jobs");

        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(identityResolver.resolveClientId(request))
                .thenReturn("ip:127.0.0.1");

        when(rateLimitService.checkLimit("ip:127.0.0.1"))
                .thenReturn(new RateLimitResult(false, 0, 12));

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertEquals("12", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString()
                .contains("Rate limit exceeded"));

        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void redisFailureShouldReturn503WithoutContinuing()
            throws Exception {

        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/jobs");

        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(identityResolver.resolveClientId(request))
                .thenReturn("ip:127.0.0.1");

        when(rateLimitService.checkLimit("ip:127.0.0.1"))
                .thenThrow(new RedisConnectionFailureException(
                        "Redis unavailable"));

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString()
                .contains("Rate limiting service unavailable"));

        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void healthEndpointShouldBypassRateLimiting()
            throws Exception {

        var request = new MockHttpServletRequest();
        request.setRequestURI("/actuator/health");

        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verifyNoInteractions(rateLimitService, identityResolver);
        verify(chain).doFilter(request, response);
    }
}