package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class RequestRateLimitFilterTest {

    private RequestRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestRateLimitFilter();
        RequestRateLimitFilter.clearBuckets();
    }

    @Test
    void platformLoginIsRateLimitedAtTenRequests() throws ServletException, IOException {
        String path = "/api/platform/auth/login";
        String ip = "192.168.1.100";

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
        }

        // 11th request exceeds limit of 10
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("600");
        assertThat(response.getContentAsString()).contains("Too many requests");
    }

    @Test
    void differentIpsHaveIndependentBuckets() throws ServletException, IOException {
        String path = "/api/platform/auth/login";

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }

        // 10.0.0.1 is now throttled
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", path);
        req1.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilter(req1, resp1, new MockFilterChain());
        assertThat(resp1.getStatus()).isEqualTo(429);

        // 10.0.0.2 is unaffected and gets 200
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", path);
        req2.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilter(req2, resp2, new MockFilterChain());
        assertThat(resp2.getStatus()).isEqualTo(200);
    }

    @Test
    void unmonitoredPathIsNotRateLimited() throws ServletException, IOException {
        String path = "/api/organization/public/citadel";
        String ip = "1.2.3.4";

        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            request.setRemoteAddr(ip);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void expiredBucketsAreEvicted() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("172.16.0.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(RequestRateLimitFilter.bucketCount()).isEqualTo(1);

        // Simulate 11 minutes in the future (> WINDOW_MS of 10 minutes)
        long elevenMinutesLater = System.currentTimeMillis() + (11 * 60 * 1000);
        RequestRateLimitFilter.evictExpired(elevenMinutesLater);

        assertThat(RequestRateLimitFilter.bucketCount()).isEqualTo(0);
    }
}
