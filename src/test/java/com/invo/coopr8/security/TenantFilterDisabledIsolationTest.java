package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.invo.coopr8.support.AbstractTwoTenantTest;
import com.invo.coopr8.support.TenantFixture;
import com.invo.coopr8.tenant.ActiveTenant;
import com.invo.coopr8.tenant.TenantContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * The same isolation guarantees, with the Hibernate tenant filter switched off.
 *
 * <p><strong>Why this class exists.</strong> The mandate requires that repository scoping be the
 * primary isolation mechanism and that the architecture stay secure if the filter is disabled or
 * removed. That is not something a code review can establish: a suite that always runs with the
 * filter on cannot tell which layer is doing the work, and if the filter is silently carrying an
 * unscoped query, removing it later -- or upgrading Hibernate, or hitting one of the cases filters
 * do not cover -- turns a passing test suite into a data leak.
 *
 * <p>So this class re-runs the load-bearing cross-tenant checks with
 * {@code coopr8.tenant.hibernate-filter.enabled=false}. Every assertion here must hold on the
 * strength of the {@code ...AndOrganizationId} repository methods alone.
 *
 * <p><strong>It cannot pass vacuously.</strong> {@link #theFilterReallyIsOffInThisContext()} proves
 * the property took effect by running a query that the filter would have restricted and observing
 * that it was not restricted. Without that guard, a typo in the property name would leave the
 * filter on and this whole class would be a duplicate of its sibling.
 *
 * <p>{@code @TestPropertySource} gives this class its own application context, so the property
 * cannot bleed into the suites that expect the filter on.
 */
@TestPropertySource(properties = "coopr8.tenant.hibernate-filter.enabled=false")
class TenantFilterDisabledIsolationTest extends AbstractTwoTenantTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // =========================================================================== vacuity guard

    @Test
    @DisplayName("The Hibernate tenant filter really is off in this context")
    void theFilterReallyIsOffInThisContext() {
        // A JPQL query is exactly what the filter restricts, and a tenant is bound, so with the
        // filter on this counts one cooperative's members (2) and with it off it counts the whole
        // table (4). Anything but 4 means this class is not testing what it claims.
        Long visible;
        TenantContext.bind(new ActiveTenant(alpha.organizationId(), ALPHA_SLUG));
        try {
            visible = new TransactionTemplate(transactionManager).execute(transaction ->
                    entityManager.createQuery("SELECT count(u) FROM User u", Long.class)
                            .getSingleResult());
        } finally {
            // Binding by hand is a thing only this test does; leaving it behind would make the
            // next request's bind() refuse to rebind and fail for an unrelated reason.
            TenantContext.clear();
        }

        assertThat(visible)
                .as("with the filter disabled an unscoped JPQL query sees every cooperative's "
                        + "rows -- which is precisely the exposure the tests below prove the "
                        + "repositories close on their own")
                .isEqualTo(4L);

        assertThat(countWhere("users")).isEqualTo(4);
    }

    // ================================================================== the isolation guarantees

    @Test
    @DisplayName("Cross-tenant reads and writes are still refused with the filter off")
    void crossTenantReadsAndWritesAreStillRefusedWithTheFilterOff() throws Exception {
        Map<String, Integer> notFourOhFour = new LinkedHashMap<>();

        // A member's token against Beta's ids.
        probe(notFourOhFour, alphaMemberToken, "GET /api/user/id/{beta}",
                get("/api/user/id/{id}", beta.member().id()), null);
        probe(notFourOhFour, alphaMemberToken, "GET /api/user/phone/{beta}",
                get("/api/user/phone/{phone}", beta.member().phone()), null);
        probe(notFourOhFour, alphaMemberToken, "GET /api/loan/{beta}",
                get("/api/loan/{id}", beta.loanId()), null);
        probe(notFourOhFour, alphaMemberToken, "GET /api/savings/{beta}",
                get("/api/savings/{id}", beta.savingId()), null);
        probe(notFourOhFour, alphaMemberToken, "PUT /api/notis/mark-read/{beta}",
                put("/api/notis/mark-read/{id}", beta.notificationId()), null);
        probe(notFourOhFour, alphaMemberToken, "DELETE /api/notis/{beta}",
                delete("/api/notis/{id}", beta.notificationId()), null);
        probe(notFourOhFour, alphaMemberToken, "PUT /api/guarantor/accept?loanId={beta}",
                put("/api/guarantor/accept").param("loanId", String.valueOf(beta.loanId())), null);

        // An administrator's token against Beta's ids: these are the ones that move money or
        // change an account, so a missing predicate here is worse than a read.
        probe(notFourOhFour, alphaAdminToken, "GET /api/admin/user/id/{beta}",
                get("/api/admin/user/id/{id}", beta.member().id()), null);
        probe(notFourOhFour, alphaAdminToken, "GET /api/admin/user/ledgerID/{beta}",
                get("/api/admin/user/ledgerID/{ledgerID}", beta.member().ledgerID()), null);
        probe(notFourOhFour, alphaAdminToken, "PUT /api/admin/user/{beta}/activate",
                put("/api/admin/user/{id}/activate", beta.member().id()), null);
        probe(notFourOhFour, alphaAdminToken, "PUT /api/admin/user/update (beta)",
                put("/api/admin/user/update"),
                Map.of("userId", beta.member().id(), "firstName", "Renamed",
                        "role", "ROLE_ADMIN", "status", "SUSPENDED"));
        probe(notFourOhFour, alphaAdminToken, "PUT /api/admin/loan/{beta}/approve",
                put("/api/admin/loan/{id}/approve", beta.loanId()), null);
        probe(notFourOhFour, alphaAdminToken, "PUT /api/shares/{beta}/approve",
                put("/api/shares/{id}/approve", beta.shareId()), null);
        probe(notFourOhFour, alphaAdminToken, "POST /api/admin/savings/manual (beta)",
                post("/api/admin/savings/manual"),
                Map.of("userId", beta.member().id(), "amount", new BigDecimal("9999.00"),
                        "note", "posted with the filter off"));
        probe(notFourOhFour, alphaAdminToken, "POST /api/admin/repays/manual (beta)",
                post("/api/admin/repays/manual"),
                Map.of("userId", beta.member().id(), "loanId", beta.loanId(),
                        "amount", new BigDecimal("9999.00")));

        assertThat(notFourOhFour)
                .as("with the filter off, the repository predicates are the only thing between "
                        + "these requests and Beta's data")
                .isEmpty();

        // And nothing was written on the way to being refused.
        assertThat(readString("SELECT status FROM shares WHERE id = ?", beta.shareId()))
                .isEqualTo("submitted");
        assertThat(readString("SELECT status FROM loan WHERE id = ?", beta.loanId()))
                .isEqualTo("submitted");
        assertThat(readString("SELECT first_name FROM users WHERE id = ?", beta.member().id()))
                .isEqualTo("Bola");
        assertThat(readString("SELECT role FROM users WHERE id = ?", beta.member().id()))
                .isEqualTo("ROLE_MEMBER");
        assertThat(passwordEncoder.matches(TenantFixture.PASSWORD,
                readString("SELECT password FROM users WHERE id = ?", beta.member().id())))
                .isTrue();
        assertThat(countWhere("saving WHERE organization_id = ?", beta.organizationId()))
                .isEqualTo(1);
        assertThat(countWhere("repay WHERE organization_id = ?", beta.organizationId()))
                .isEqualTo(1);

        // Positive control: the same endpoints work inside the caller's own cooperative, so the
        // 404s above are about the tenant and not about a context that failed to start properly.
        as(alphaMemberToken, get("/api/loan/{id}", alpha.loanId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alpha.loanId()));
        as(alphaAdminToken, put("/api/shares/{id}/approve", alpha.shareId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("100"));
    }

    @Test
    @DisplayName("Administrative lists are still tenant-scoped with the filter off")
    void administrativeListsAreStillTenantScopedWithTheFilterOff() throws Exception {
        // These are the six endpoints that used to call findAll(). The filter would have hidden a
        // regression here completely: findAll() with the filter on returns one cooperative's rows
        // and looks correct.
        as(alphaAdminToken, get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == " + beta.member().id() + ")]").doesNotExist());

        for (String uri : List.of(
                "/api/admin/loan/all",
                "/api/admin/repays/all",
                "/api/admin/savings/all",
                "/api/admin/shares/all",
                "/api/admin/notis/all")) {

            as(alphaAdminToken, get(uri))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }

        // The per-member collections answer with an empty list for a foreign member id.
        for (String uri : List.of(
                "/api/admin/loan/loansbyuserid/{id}",
                "/api/admin/repays/user/{id}",
                "/api/admin/savings/user/{id}",
                "/api/admin/shares/user/{id}")) {

            as(alphaAdminToken, get(uri, beta.member().id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
            as(alphaAdminToken, get(uri, alpha.member().id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    @Test
    @DisplayName("Bulk operations are still tenant-scoped with the filter off")
    void bulkOperationsAreStillTenantScopedWithTheFilterOff() throws Exception {
        // A bulk UPDATE or DELETE issued through a @Query is the case where relying on the filter
        // is most tempting and least safe: Hibernate applies filters to selects, and a hand-written
        // modifying query is on its own.
        as(alphaMemberToken, put("/api/notis/mark-all-read")).andExpect(status().isOk());
        as(alphaMemberToken, delete("/api/notis/user/delete")).andExpect(status().isOk());

        assertThat(countWhere("notification WHERE id = ?", beta.notificationId())).isEqualTo(1);
        assertThat(readBoolean("SELECT is_read FROM notification WHERE id = ?",
                beta.notificationId())).isFalse();
        assertThat(countWhere("notification WHERE organization_id = ?", alpha.organizationId()))
                .isZero();
    }

    @Test
    @DisplayName("Shared identifiers still resolve the caller's own member with the filter off")
    void sharedIdentifiersStillResolveTheCallersOwnMemberWithTheFilterOff() throws Exception {
        // The payroll ledger upload and the phone lookup both match on a value that is unique per
        // cooperative and not platform-wide, so with the filter off an unscoped match would find
        // two rows and take one.
        as(alphaAdminToken, post("/api/admin/ledger/batch"), Map.of("rows", List.of(Map.of(
                "rowNumber", 1,
                "psn", TenantFixture.SHARED_MEMBER_PSN,
                "savingAmount", new BigDecimal("2500.00"),
                "repayAmount", new BigDecimal("1500.00")))))
                .andExpect(status().isOk());

        assertThat(countWhere("saving WHERE user_id = ? AND amount = 2500.00", alpha.member().id()))
                .isEqualTo(1);
        assertThat(countWhere("saving WHERE user_id = ?", beta.member().id())).isEqualTo(1);
        assertThat(countWhere("repay WHERE user_id = ?", beta.member().id())).isEqualTo(1);

        String sharedPhone = alpha.member().phone();
        jdbcTemplate.update("UPDATE users SET phone = ? WHERE id = ?",
                sharedPhone, beta.member().id());

        as(alphaMemberToken, get("/api/user/phone/{phone}", sharedPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerID").value(alpha.member().ledgerID()));
        as(betaMemberToken, get("/api/user/phone/{phone}", sharedPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerID").value(beta.member().ledgerID()));
    }

    @Test
    @DisplayName("A reset code is still tenant-scoped with the filter off")
    void aResetCodeIsStillTenantScopedWithTheFilterOff() throws Exception {
        // OTP carries the filter too, and the two members share an email address, so with the
        // filter off the only thing keeping Alpha's code out of Beta's reset is the organization
        // predicate in the OTP queries themselves.
        String issued = mockMvc.perform(post("/api/otp/sendOTP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("organization", ALPHA_SLUG,
                                "email", TenantFixture.SHARED_MEMBER_EMAIL,
                                "action", "forgetpass"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(issued).path("responseCode").asText()).isEqualTo("100");

        String alphasCode = jdbcTemplate.queryForObject(
                "SELECT otp FROM otp WHERE organization_id = ? AND purpose = 'PASSWORD_RESET'",
                String.class, alpha.organizationId());

        String replayed = mockMvc.perform(post("/api/auth/forgot-password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("organization", BETA_SLUG,
                                "ledgerID", beta.member().ledgerID(),
                                "otp", alphasCode,
                                "newPassword", "NewPassw0rd!",
                                "confirmPassword", "NewPassw0rd!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(replayed).path("responseCode").asText())
                .as("Alpha's code must not reset Beta's member, filter or no filter")
                .isEqualTo("419");
        assertThat(passwordEncoder.matches(TenantFixture.PASSWORD,
                readString("SELECT password FROM users WHERE id = ?", beta.member().id())))
                .isTrue();
    }

    // ==================================================================================== helpers

    private void probe(Map<String, Integer> failures, String bearerToken, String label,
            MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        int status = mockMvc.perform(request.header("Authorization", bearerToken))
                .andReturn().getResponse().getStatus();
        if (status != 404) {
            failures.put(label, status);
        }
    }

    private String readString(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, String.class, argument);
    }

    private boolean readBoolean(String sql, Object argument) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, argument));
    }
}
