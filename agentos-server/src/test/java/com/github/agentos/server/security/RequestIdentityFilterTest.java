package com.github.agentos.server.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdentityFilterTest {

    @Test
    void ignoresUntrustedIdentityHeaders() throws Exception {
        RequestIdentityFilter filter = new RequestIdentityFilter(
                "fixed-team", "fixed-user", "MEMORY_ADMIN", false);
        MockHttpServletRequest request = requestWithSpoofedHeaders();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        RequestIdentity identity = (RequestIdentity) request.getAttribute(
                RequestIdentity.REQUEST_ATTRIBUTE);
        assertThat(identity.teamId()).isEqualTo("fixed-team");
        assertThat(identity.userId()).isEqualTo("fixed-user");
        assertThat(identity.memoryAdmin()).isTrue();
    }

    @Test
    void bindsGatewayHeadersOnlyWhenExplicitlyTrusted() throws Exception {
        RequestIdentityFilter filter = new RequestIdentityFilter(
                "fixed-team", "fixed-user", "", true);
        MockHttpServletRequest request = requestWithSpoofedHeaders();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        RequestIdentity identity = (RequestIdentity) request.getAttribute(
                RequestIdentity.REQUEST_ATTRIBUTE);
        assertThat(identity.teamId()).isEqualTo("header-team");
        assertThat(identity.userId()).isEqualTo("header-user");
        assertThat(identity.memoryAdmin()).isTrue();
    }

    private static MockHttpServletRequest requestWithSpoofedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/memories");
        request.addHeader(RequestIdentityFilter.TEAM_HEADER, "header-team");
        request.addHeader(RequestIdentityFilter.USER_HEADER, "header-user");
        request.addHeader(RequestIdentityFilter.ROLES_HEADER, "memory_admin, viewer");
        return request;
    }
}
