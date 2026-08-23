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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.invo.coopr8.support.AbstractTwoTenantTest;
import com.invo.coopr8.support.TenantFixture;

/**
 * The cross-tenant (IDOR) matrix: every tenant-owned resource, requested with a valid token from
 * the wrong cooperative.
 *
 * <p><strong>The expected answer is 404, not 403.</strong> A 403 confirms the resource exists,
 * which is itself a disclosure -- an attacker who can enumerate ids learns how many members, loans
 * and withdrawals another cooperative has, and can watch that number grow. A 404 says the same
 * thing to a caller with the wrong tenant as it says for an id that was never issued.
 *
 * <p><strong>Two things are asserted for every probe, not one.</strong> The status code, and that
 * the target row is unchanged afterwards. A status code alone is a weak assertion for a mutating
 * endpoint: an approval that moved money and <em>then</em> failed a check would still answer 404.
 * {@link #assertBetaIsExactlyAsItWasSeeded()} reads the other cooperative's rows straight out of
 * the database, past every application-level filter, and is called at the end of each probe test.
 *
 * <p><strong>Requests go through the real filter chain.</strong> {@code @AutoConfigureMockMvc}
 * without {@code addFilters = false}, and the tokens come from {@code POST /api/auth/login}. So
 * these are API-level tests in the sense the mandate requires: nothing here calls a service
 * directly, and no test would pass because a frontend route was guarded.
 */
class CrossTenantIsolationTest extends AbstractTwoTenantTest {

    /** One request that must be refused, with a label that identifies it in a failure. */
    private record Probe(String label, MockHttpServletRequestBuilder request, Object body) {

        static Probe of(String label, MockHttpServletRequestBuilder request) {
            return new Probe(label, request, null);
        }

        static Probe of(String label, MockHttpServletRequestBuilder request, Object body) {
            return new Probe(label, request, body);
        }
    }

    // ============================================================ a member's token, Beta's ids

    @Test
    @DisplayName("A member's token reaches nothing in another cooperative")
    void aMembersTokenReachesNothingInAnotherCooperative() throws Exception {
        List<Probe> probes = List.of(
                Probe.of("GET /api/user/id/{betaMember}",
                        get("/api/user/id/{id}", beta.member().id())),
                Probe.of("GET /api/user/phone/{betaMemberPhone}",
                        get("/api/user/phone/{phone}", beta.member().phone())),
                Probe.of("GET /api/loan/{betaLoan}",
                        get("/api/loan/{id}", beta.loanId())),
                Probe.of("POST /api/loan/{betaLoan}/repay",
                        post("/api/loan/{id}/repay", beta.loanId()),
                        Map.of("amount", new BigDecimal("1000.00"), "type", "normal")),
                Probe.of("GET /api/savings/{betaSaving}",
                        get("/api/savings/{id}", beta.savingId())),
                Probe.of("PUT /api/notis/mark-read/{betaNotification}",
                        put("/api/notis/mark-read/{id}", beta.notificationId())),
                Probe.of("DELETE /api/notis/{betaNotification}",
                        delete("/api/notis/{id}", beta.notificationId())),
                Probe.of("PUT /api/guarantor/accept?loanId={betaLoan}",
                        put("/api/guarantor/accept").param("loanId", String.valueOf(beta.loanId()))),
                Probe.of("PUT /api/guarantor/decline?loanId={betaLoan}",
                        put("/api/guarantor/decline").param("loanId",
                                String.valueOf(beta.loanId()))));

        assertThat(statusesOf(alphaMemberToken, probes))
                .as("each of these must answer 404 -- anything else either serves another "
                        + "cooperative's data or confirms that it exists")
                .isEmpty();

        assertBetaIsExactlyAsItWasSeeded();
    }

    // ====================================================== an administrator's token, Beta's ids

    @Test
    @DisplayName("An administrator's token reaches nothing in another cooperative")
    void anAdministratorsTokenReachesNothingInAnotherCooperative() throws Exception {
        List<Probe> probes = List.of(
                Probe.of("GET /api/admin/user/id/{betaMember}",
                        get("/api/admin/user/id/{id}", beta.member().id())),
                Probe.of("GET /api/admin/user/ledgerID/{betaLedgerID}",
                        get("/api/admin/user/ledgerID/{ledgerID}", beta.member().ledgerID())),
                Probe.of("PUT /api/admin/user/{betaMember}/activate",
                        put("/api/admin/user/{id}/activate", beta.member().id())),
                Probe.of("PUT /api/admin/user/update (userId = betaMember)",
                        put("/api/admin/user/update"),
                        Map.of("userId", beta.member().id(),
                                "firstName", "Renamed",
                                "role", "ROLE_ADMIN",
                                "status", "SUSPENDED")),
                Probe.of("PUT /api/admin/loan/{betaLoan}/approve",
                        put("/api/admin/loan/{id}/approve", beta.loanId())),
                Probe.of("PUT /api/admin/loan/{betaLoan}/reject",
                        put("/api/admin/loan/{id}/reject", beta.loanId()),
                        Map.of("remark", "rejected from another cooperative")),
                Probe.of("PUT /api/shares/{betaShare}/approve",
                        put("/api/shares/{id}/approve", beta.shareId())),
                Probe.of("POST /api/shares/{betaShare}/decline",
                        post("/api/shares/{id}/decline", beta.shareId())),
                Probe.of("POST /api/admin/savings/manual (userId = betaMember)",
                        post("/api/admin/savings/manual"),
                        Map.of("userId", beta.member().id(),
                                "amount", new BigDecimal("9999.00"),
                                "note", "posted from another cooperative")),
                Probe.of("POST /api/admin/repays/manual (userId = betaMember)",
                        post("/api/admin/repays/manual"),
                        Map.of("userId", beta.member().id(),
                                "loanId", beta.loanId(),
                                "amount", new BigDecimal("9999.00"))));

        assertThat(statusesOf(alphaAdminToken, probes))
                .as("an administrator is an administrator of one cooperative; the role does not "
                        + "widen to the platform")
                .isEmpty();

        assertBetaIsExactlyAsItWasSeeded();
    }

    // ======================================================================= positive controls

    @Test
    @DisplayName("The same requests succeed inside the caller's own cooperative")
    void theSameRequestsSucceedInsideTheCallersOwnCooperative() throws Exception {
        // Without this, every 404 above could be explained by a broken endpoint, a wrong URL, or
        // a token the chain rejects outright -- and the suite would pass while isolating nothing.
        as(alphaMemberToken, get("/api/user/id/{id}", alpha.member().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alpha.member().id()));

        as(alphaMemberToken, get("/api/user/phone/{phone}", alpha.member().phone()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerID").value(alpha.member().ledgerID()));

        as(alphaMemberToken, get("/api/loan/{id}", alpha.loanId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alpha.loanId()));

        as(alphaMemberToken, get("/api/savings/{id}", alpha.savingId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alpha.savingId()));

        as(alphaMemberToken, put("/api/notis/mark-read/{id}", alpha.notificationId()))
                .andExpect(status().isOk());

        as(alphaAdminToken, get("/api/admin/user/id/{id}", alpha.member().id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerID").value(alpha.member().ledgerID()));

        as(alphaAdminToken, get("/api/admin/user/ledgerID/{ledgerID}", alpha.member().ledgerID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(alpha.member().id()));

        as(alphaAdminToken, put("/api/shares/{id}/approve", alpha.shareId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("100"));

        assertBetaIsExactlyAsItWasSeeded();
    }

    // ================================================================== collections and bulk ops

    @Test
    @DisplayName("Collection endpoints asked for another cooperative's member come back empty")
    void collectionEndpointsAskedForAnotherCooperativesMemberComeBackEmpty() throws Exception {
        // These four take a member id and answer with a list. An empty list is as
        // non-revealing as a 404 here -- it is the same answer an id that was never issued
        // gets -- but it must be empty, not populated with Beta's rows.
        for (String uri : List.of(
                "/api/admin/loan/loansbyuserid/{id}",
                "/api/admin/repays/user/{id}",
                "/api/admin/savings/user/{id}",
                "/api/admin/shares/user/{id}")) {

            as(alphaAdminToken, get(uri, beta.member().id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$.length()").value(0));

            // Same endpoint, own member: proves the emptiness above is about the tenant.
            as(alphaAdminToken, get(uri, alpha.member().id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1));
        }
    }

    @Test
    @DisplayName("Bulk notification operations stop at the tenant boundary")
    void bulkNotificationOperationsStopAtTheTenantBoundary() throws Exception {
        // Bulk UPDATE and DELETE are the quietest place for a missing tenant predicate: no id is
        // named in the request, so nothing looks wrong at the call site, and the blast radius is
        // every row in the table.
        as(alphaMemberToken, put("/api/notis/mark-all-read")).andExpect(status().isOk());
        as(alphaMemberToken, delete("/api/notis/user/delete")).andExpect(status().isOk());

        assertThat(countWhere("notification WHERE organization_id = ?", alpha.organizationId()))
                .as("Alpha's own notification was the one being deleted")
                .isZero();
        assertThat(countWhere("notification WHERE id = ?", beta.notificationId()))
                .as("Beta's notification must survive a bulk delete issued by Alpha")
                .isEqualTo(1);
        assertThat(readBoolean("SELECT is_read FROM notification WHERE id = ?",
                beta.notificationId()))
                .as("nor may Alpha's mark-all-read have marked it")
                .isFalse();
    }

    // ============================================================== identity-shaped collisions

    @Test
    @DisplayName("A PSN shared across cooperatives credits only the caller's own member")
    void aPsnSharedAcrossCooperativesCreditsOnlyTheCallersOwnMember() throws Exception {
        // The payroll ledger upload matches members by PSN, and a PSN is unique per cooperative,
        // not platform-wide. This is the case the old global findFirstByPsn got wrong: two
        // members with the same payroll number, and the deduction posted to whichever row the
        // database happened to return first.
        Map<String, Object> row = Map.of(
                "rowNumber", 1,
                "psn", TenantFixture.SHARED_MEMBER_PSN,
                "savingAmount", new BigDecimal("2500.00"),
                "repayAmount", new BigDecimal("1500.00"));

        as(alphaAdminToken, post("/api/admin/ledger/batch"), Map.of("rows", List.of(row)))
                .andExpect(status().isOk());

        assertThat(countWhere("saving WHERE user_id = ? AND amount = 2500.00",
                alpha.member().id()))
                .as("Alpha's upload must credit Alpha's member")
                .isEqualTo(1);
        assertThat(countWhere("saving WHERE user_id = ?", beta.member().id()))
                .as("and must not have touched the Beta member who shares that PSN")
                .isEqualTo(1);  // the single row the fixture seeded
        assertThat(countWhere("repay WHERE user_id = ?", beta.member().id()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("A phone number shared across cooperatives resolves to the caller's own member")
    void aPhoneNumberSharedAcrossCooperativesResolvesToTheCallersOwnMember() throws Exception {
        // Guarantor nomination looks members up by phone number, and nothing stops the same
        // number appearing in two cooperatives.
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
    @DisplayName("The organization endpoint describes the caller's own cooperative")
    void theOrganizationEndpointDescribesTheCallersOwnCooperative() throws Exception {
        as(alphaMemberToken, get("/api/organization/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(ALPHA_SLUG));

        as(betaMemberToken, get("/api/organization/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(BETA_SLUG));
    }

    // ==================================================================================== helpers

    /**
     * Runs every probe with the given token and returns the ones that did <em>not</em> answer 404,
     * so a failure names all of them at once rather than stopping at the first.
     */
    private Map<String, Integer> statusesOf(String bearerToken, List<Probe> probes)
            throws Exception {
        Map<String, Integer> notFourOhFour = new LinkedHashMap<>();

        for (Probe probe : probes) {
            MockHttpServletRequestBuilder request = probe.request();
            if (probe.body() != null) {
                request = request
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(probe.body()));
            }

            int status = mockMvc.perform(request.header("Authorization", bearerToken))
                    .andReturn().getResponse().getStatus();

            if (status != 404) {
                notFourOhFour.put(probe.label(), status);
            }
        }
        return notFourOhFour;
    }

    /**
     * Reads Beta's rows directly, past the repositories and the Hibernate filter.
     *
     * <p>Called after every probe test, because a refused request is only half the guarantee: the
     * other half is that nothing was written on the way to being refused.
     */
    private void assertBetaIsExactlyAsItWasSeeded() {
        assertThat(readString("SELECT status FROM shares WHERE id = ?", beta.shareId()))
                .as("a cross-tenant approval must not have moved Beta's withdrawal along")
                .isEqualTo("submitted");
        assertThat(readString("SELECT status FROM loan WHERE id = ?", beta.loanId()))
                .isEqualTo("submitted");
        assertThat(readString("SELECT guarantor1status FROM loan WHERE id = ?", beta.loanId()))
                .isEqualTo("PENDING");
        assertThat(readBoolean("SELECT is_read FROM notification WHERE id = ?",
                beta.notificationId()))
                .isFalse();
        assertThat(countWhere("notification WHERE id = ?", beta.notificationId())).isEqualTo(1);

        assertThat(readString("SELECT first_name FROM users WHERE id = ?", beta.member().id()))
                .as("the admin update probe named Beta's member explicitly")
                .isEqualTo("Bola");
        assertThat(readString("SELECT role FROM users WHERE id = ?", beta.member().id()))
                .isEqualTo("ROLE_MEMBER");
        assertThat(readString("SELECT status FROM users WHERE id = ?", beta.member().id()))
                .isEqualTo("ACTIVE");
        assertThat(passwordEncoder.matches(TenantFixture.PASSWORD,
                readString("SELECT password FROM users WHERE id = ?", beta.member().id())))
                .as("activation issues a temporary password; a cross-tenant activation would have "
                        + "locked Beta's member out of their own account")
                .isTrue();

        assertThat(countWhere("saving WHERE organization_id = ?", beta.organizationId()))
                .as("no manual posting or ledger upload may add a row to Beta")
                .isEqualTo(1);
        assertThat(countWhere("repay WHERE organization_id = ?", beta.organizationId()))
                .isEqualTo(1);

        assertThat(recordedEmails.sent().stream()
                .filter(email -> email.mentions(beta.slug()))
                .toList())
                .as("and no mail sent while serving Alpha may name Beta -- the fixture's two "
                        + "members share an email address, so the recipient cannot be the "
                        + "discriminator; the cooperative's own name in the body is")
                .isEmpty();
    }

    private String readString(String sql, Object argument) {
        return jdbcTemplate.queryForObject(sql, String.class, argument);
    }

    private boolean readBoolean(String sql, Object argument) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, argument));
    }
}
