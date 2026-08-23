package com.invo.coopr8.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.invo.coopr8.config.JwtConstant;
import com.invo.coopr8.support.TenantFixture.Tenant;

/**
 * Base class for the cross-tenant tests: two complete cooperatives, and a real token for each of
 * the four people in them.
 *
 * <p>The tokens are obtained by posting to {@code /api/auth/login}, not minted in the test. That
 * matters: it means every request these tests make carries a token the application itself issued,
 * with whatever tenant claim the login path decided to put in it. A hand-built token would test
 * the filter chain against the test's idea of a token rather than the application's.
 *
 * <p>Alpha and Beta differ in slug <em>and</em> ledger prefix, because both are tenant
 * discriminators at login: the slug is what the organization-specific URL supplies, and the prefix
 * is the fallback that keeps existing membership numbers working. Two tenants sharing either one
 * would make the login tests ambiguous.
 */
public abstract class AbstractTwoTenantTest extends AbstractIntegrationTest {

    protected static final String ALPHA_SLUG = "alpha-coop";
    protected static final String ALPHA_PREFIX = "ALPHA";
    protected static final String BETA_SLUG = "beta-coop";
    protected static final String BETA_PREFIX = "BETA";

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected Tenant alpha;
    protected Tenant beta;

    /** {@code Authorization} header values, ready to send. */
    protected String alphaMemberToken;
    protected String alphaAdminToken;
    protected String betaMemberToken;
    protected String betaAdminToken;

    @BeforeEach
    void seedTwoCooperativesAndSignEveryoneIn() throws Exception {
        // Runs after AbstractIntegrationTest's truncate: JUnit invokes superclass @BeforeEach
        // methods first, so the tables are empty by the time these inserts happen.
        alpha = TenantFixture.seed(jdbcTemplate, passwordEncoder, ALPHA_SLUG, ALPHA_PREFIX, "0801");
        beta = TenantFixture.seed(jdbcTemplate, passwordEncoder, BETA_SLUG, BETA_PREFIX, "0802");

        alphaMemberToken = signIn(ALPHA_SLUG, alpha.member().ledgerID());
        alphaAdminToken = signIn(ALPHA_SLUG, alpha.admin().ledgerID());
        betaMemberToken = signIn(BETA_SLUG, beta.member().ledgerID());
        betaAdminToken = signIn(BETA_SLUG, beta.admin().ledgerID());

        // The fixture's emails collide across tenants by design; if that ever stopped being true
        // the OTP and reset tests below would still pass, but they would prove nothing.
        assertThat(alpha.member().email()).isEqualTo(beta.member().email());
        assertThat(alpha.member().psn()).isEqualTo(beta.member().psn());
    }

    /** Signs in through the real login endpoint and returns an {@code Authorization} value. */
    protected String signIn(String organizationSlug, String ledgerID) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginBody(organizationSlug, ledgerID,
                                TenantFixture.PASSWORD))))
                .andReturn().getResponse().getContentAsString();

        String jwt = objectMapper.readTree(response).path("jwt").asText(null);
        assertThat(jwt)
                .as("%s/%s must be able to sign in, or nothing below is testing what it claims: "
                        + "login said %s", organizationSlug, ledgerID, response)
                .isNotBlank();

        return JwtConstant.BEARER_PREFIX + jwt;
    }

    /** Performs a request as the holder of {@code bearerToken}. */
    protected ResultActions as(String bearerToken, MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearerToken));
    }

    /** Performs a request with a JSON body as the holder of {@code bearerToken}. */
    protected ResultActions as(String bearerToken, MockHttpServletRequestBuilder request,
            Object body) throws Exception {
        return mockMvc.perform(request
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    protected String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    /** Convenience so subclasses need not import MockMvcRequestBuilders one by one. */
    protected static MockHttpServletRequestBuilder post(String uri, Object... uriVariables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(uri, uriVariables);
    }

    protected static MockHttpServletRequestBuilder get(String uri, Object... uriVariables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get(uri, uriVariables);
    }

    protected static MockHttpServletRequestBuilder put(String uri, Object... uriVariables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put(uri, uriVariables);
    }

    protected static MockHttpServletRequestBuilder delete(String uri, Object... uriVariables) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete(uri, uriVariables);
    }

    /**
     * Mirrors {@code LoginDto}'s JSON shape without depending on its setters.
     *
     * <p>{@code public}, not {@code protected}: a protected constructor is only reachable through
     * {@code super()}, so a subclass in another package could not build one.
     */
    public record LoginBody(String organization, String ledgerID, String password) {
    }

    /** Reads a row count straight out of the database, bypassing every application filter. */
    protected long countWhere(String sql, Object... arguments) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + sql, Long.class,
                arguments);
        return count == null ? 0L : count;
    }
}
