package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.invo.coopr8.support.AbstractTwoTenantTest;
import com.invo.coopr8.support.TenantFixture;

/**
 * The four authorization defects the Phase 2 mandate named, each turned into a test that would
 * have failed before the fix.
 *
 * <ol>
 *   <li><strong>Administrative list endpoints returned the whole platform.</strong>
 *       {@code findAll()} on six endpoints, so every administrator could read every cooperative's
 *       members, loans, repayments, savings, shares and notifications.
 *   <li><strong>Share withdrawal approval was open to any authenticated caller.</strong> A member
 *       could approve their own withdrawal.
 *   <li><strong>Password hashes were serialized.</strong> {@code User} was returned directly from
 *       a dozen endpoints, hash included -- offline-crackable, and the same hash unlocks the
 *       account.
 *   <li><strong>Profile update bound the whole entity.</strong> {@code "role": "ROLE_ADMIN"} in a
 *       self-service profile body was all it took to become an administrator.
 * </ol>
 *
 * <p>Where the defect was a missing tenant predicate, the assertion is about <em>counts and ids</em>
 * rather than status codes: the old endpoints answered 200 and were wrong, so only the contents of
 * the response distinguish fixed from broken.
 */
class AuthorizationBoundaryTest extends AbstractTwoTenantTest {

    /** A JSON key named password, however the serializer spelled the value. */
    private static final Pattern PASSWORD_KEY = Pattern.compile("\"password\"\\s*:");

    /** BCrypt's version markers. Any of these in a response body is a hash in a response body. */
    private static final List<String> BCRYPT_MARKERS = List.of("$2a$", "$2b$", "$2y$");

    // ================================================== 1. administrative lists are tenant-scoped

    @Test
    @DisplayName("Administrative list endpoints contain only the caller's own cooperative")
    void administrativeListEndpointsContainOnlyTheCallersOwnCooperative() throws Exception {
        // Both cooperatives hold exactly one of each row, so "the platform" and "my cooperative"
        // are two different numbers -- which is what makes these assertions able to fail.
        as(alphaAdminToken, get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))          // Alpha's admin and member
                .andExpect(jsonPath("$[?(@.id == " + beta.member().id() + ")]").doesNotExist())
                .andExpect(jsonPath("$[?(@.id == " + beta.admin().id() + ")]").doesNotExist());

        assertOneRowAndNotBetas("/api/admin/loan/all", beta.loanId());
        assertOneRowAndNotBetas("/api/admin/repays/all", beta.repayId());
        assertOneRowAndNotBetas("/api/admin/savings/all", beta.savingId());
        assertOneRowAndNotBetas("/api/admin/shares/all", beta.shareId());
        assertOneRowAndNotBetas("/api/admin/notis/all", beta.notificationId());

        // And the mirror image: Beta's administrator sees Beta's row, not Alpha's. A single-sided
        // check can pass on an endpoint that always returns the first cooperative it finds.
        as(betaAdminToken, get("/api/admin/loan/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(beta.loanId()));
    }

    @Test
    @DisplayName("A member cannot reach the administrative surface at all")
    void aMemberCannotReachTheAdministrativeSurfaceAtAll() throws Exception {
        // 403 rather than 404 here on purpose: the route exists and the caller is authenticated,
        // they are simply not an administrator. Nothing about another cooperative is disclosed by
        // saying so, and a 404 would make a genuine misconfiguration indistinguishable from a
        // permission decision.
        for (String uri : List.of(
                "/api/admin/users",
                "/api/admin/loan/all",
                "/api/admin/repays/all",
                "/api/admin/savings/all",
                "/api/admin/shares/all",
                "/api/admin/notis/all",
                "/api/admin/users/new")) {

            as(alphaMemberToken, get(uri))
                    .andExpect(status().isForbidden());
        }

        as(alphaMemberToken, put("/api/admin/user/{id}/activate", alpha.member().id()))
                .andExpect(status().isForbidden());
        as(alphaMemberToken, put("/api/admin/user/update"),
                Map.of("userId", alpha.member().id(), "role", "ROLE_ADMIN"))
                .andExpect(status().isForbidden());
    }

    // ============================================================== 2. share approval is admin-only

    @Test
    @DisplayName("A member cannot approve or decline a share withdrawal -- not even their own")
    void aMemberCannotApproveOrDeclineAShareWithdrawal() throws Exception {
        // The withdrawal belongs to this very member, so nothing but the authorization rule is
        // standing in the way. Approving it moves money out of the cooperative.
        as(alphaMemberToken, put("/api/shares/{id}/approve", alpha.shareId()))
                .andExpect(status().isForbidden());
        as(alphaMemberToken, post("/api/shares/{id}/decline", alpha.shareId()))
                .andExpect(status().isForbidden());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM shares WHERE id = ?",
                String.class, alpha.shareId()))
                .as("the withdrawal must still be awaiting an administrator")
                .isEqualTo("submitted");

        // The rule is enforced twice -- at the route in the security chain and again in
        // SharesServiceImpl.requireApprovableShare via CurrentAuth.requireAdmin() -- so that
        // neither layer is the only thing between a member and their own approval. The route wins
        // first over HTTP, which is why this test sees 403; the service-level guard is what would
        // still refuse if the route matcher were ever edited or reordered.
        as(alphaAdminToken, put("/api/shares/{id}/approve", alpha.shareId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responseCode").value("100"));
    }

    // ==================================================== 3. password hashes are never serialized

    @Test
    @DisplayName("No endpoint serializes a password hash")
    void noEndpointSerializesAPasswordHash() throws Exception {
        String storedHash = jdbcTemplate.queryForObject(
                "SELECT password FROM users WHERE id = ?", String.class, alpha.member().id());
        assertThat(storedHash)
                .as("the fixture must have stored a real BCrypt hash, or this test is looking "
                        + "for a string that was never there")
                .startsWith("$2");

        Map<String, String> bodies = new LinkedHashMap<>();

        for (String uri : List.of(
                "/api/auth/profile",
                "/api/user/id/" + alpha.member().id(),
                "/api/user/phone/" + alpha.member().phone(),
                "/api/loan/myloans",
                "/api/loan/" + alpha.loanId(),
                "/api/loan/myrepays",
                "/api/savings/mysavings",
                "/api/savings/" + alpha.savingId(),
                "/api/savings/last",
                "/api/shares/my",
                "/api/notis/mynotis",
                "/api/notis/user/unread",
                "/api/guarantor/myrequests",
                "/api/organization/current")) {
            bodies.put("GET " + uri + " (member)", body(alphaMemberToken, get(uri), null));
        }

        for (String uri : List.of(
                "/api/admin/users",
                "/api/admin/users/new",
                "/api/admin/user/id/" + alpha.member().id(),
                "/api/admin/user/ledgerID/" + alpha.member().ledgerID(),
                "/api/admin/loan/all",
                "/api/admin/loan/loansbyuserid/" + alpha.member().id(),
                "/api/admin/repays/all",
                "/api/admin/repays/user/" + alpha.member().id(),
                "/api/admin/savings/all",
                "/api/admin/savings/user/" + alpha.member().id(),
                "/api/admin/shares/all",
                "/api/admin/shares/user/" + alpha.member().id(),
                "/api/admin/notis/all",
                // The administrator stands as guarantor on the member's loan, so this one
                // serializes a Loan with a borrower attached -- the nested path that a
                // @JsonIgnore on the field, rather than a hand-written DTO, is there to cover.
                "/api/guarantor/myrequests")) {
            bodies.put("GET " + uri + " (admin)", body(alphaAdminToken, get(uri), null));
        }

        // Write paths return the updated entity, so they are serialization paths too.
        bodies.put("PUT /api/user/update", body(alphaMemberToken, put("/api/user/update"),
                Map.of("firstName", "Bola", "lastName", "Adewale", "phone",
                        alpha.member().phone())));
        bodies.put("PUT /api/admin/user/update", body(alphaAdminToken, put("/api/admin/user/update"),
                Map.of("userId", alpha.member().id(), "firstName", "Bola")));
        bodies.put("POST /api/auth/signup", body(null, post("/api/auth/signup"), signupBody()));

        List<String> leaks = new ArrayList<>();
        bodies.forEach((label, responseBody) -> {
            if (responseBody == null || responseBody.isBlank()) {
                return;
            }
            if (responseBody.contains(storedHash)) {
                leaks.add(label + " -- contains the stored hash verbatim");
            }
            if (PASSWORD_KEY.matcher(responseBody).find()) {
                leaks.add(label + " -- has a \"password\" key");
            }
            BCRYPT_MARKERS.stream()
                    .filter(responseBody::contains)
                    .findFirst()
                    .ifPresent(marker -> leaks.add(label + " -- contains a BCrypt hash (" + marker
                            + ")"));
        });

        assertThat(leaks)
                .as("a hash in a response is offline-crackable and unlocks the account it came "
                        + "from; these are every endpoint that serializes a User, directly or "
                        + "nested inside a loan, saving, share or notification")
                .isEmpty();

        assertThat(bodies)
                .as("the sweep must actually have collected responses")
                .hasSizeGreaterThan(25);
    }

    // ============================================================= 4. no self-service escalation

    @Test
    @DisplayName("A member cannot promote themselves, or edit any field they do not own")
    void aMemberCannotPromoteThemselvesOrEditAnyFieldTheyDoNotOwn() throws Exception {
        Map<String, Object> hostileBody = new LinkedHashMap<>();
        // The fields a profile edit legitimately carries.
        hostileBody.put("firstName", "Bola");
        hostileBody.put("lastName", "Adewale");
        hostileBody.put("phone", alpha.member().phone());
        // ...and every field it must not.
        hostileBody.put("role", "ROLE_ADMIN");
        hostileBody.put("status", "ACTIVE");
        hostileBody.put("ledgerID", "ALPHA9999");
        hostileBody.put("id", alpha.admin().id());
        hostileBody.put("userId", alpha.admin().id());
        hostileBody.put("organization", BETA_SLUG);
        hostileBody.put("organizationId", beta.organizationId());
        hostileBody.put("password", "chosen-by-me");
        hostileBody.put("savingsBalance", new BigDecimal("999999.00"));
        hostileBody.put("loanBalance", new BigDecimal("0.00"));
        hostileBody.put("sharesBalance", new BigDecimal("999999.00"));
        hostileBody.put("profit", new BigDecimal("999999.00"));

        int status = mockMvc.perform(put("/api/user/update")
                        .header("Authorization", alphaMemberToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(hostileBody)))
                .andReturn().getResponse().getStatus();

        assertThat(status)
                .as("the extra fields are either ignored (200) or rejected outright (400); what "
                        + "must not happen is that they are applied")
                .isIn(200, 400);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT role, status, ledgerid, organization_id, password, savings_balance, "
                        + "loan_balance, shares_balance FROM users WHERE id = ?",
                alpha.member().id());

        assertThat(row.get("role")).as("the escalation this test exists for")
                .isEqualTo("ROLE_MEMBER");
        assertThat(row.get("ledgerid")).isEqualTo(alpha.member().ledgerID());
        assertThat(((Number) row.get("organization_id")).longValue())
                .as("a member may not move themselves into another cooperative either")
                .isEqualTo(alpha.organizationId());
        assertThat(passwordEncoder.matches(TenantFixture.PASSWORD, (String) row.get("password")))
                .as("password changes go through changepass, which requires the old password")
                .isTrue();
        assertThat((BigDecimal) row.get("savings_balance"))
                .as("balances are the ledger's business, not the profile form's")
                .isEqualByComparingTo("5000.00");
        assertThat((BigDecimal) row.get("shares_balance")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) row.get("loan_balance")).isEqualByComparingTo("110000.00");

        // The administrator's record was named in the body by both `id` and `userId`.
        assertThat(jdbcTemplate.queryForObject("SELECT first_name FROM users WHERE id = ?",
                String.class, alpha.admin().id()))
                .as("and the body must not have redirected the update onto another member")
                .isEqualTo("Ada");

        // The token still says ROLE_MEMBER, and so must the next request's authorities.
        as(alphaMemberToken, get("/api/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An administrator's own role and status are not theirs to edit")
    void anAdministratorsOwnRoleAndStatusAreNotTheirsToEdit() throws Exception {
        // An administrator legitimately edits roles -- but not their own, or a compromised admin
        // session could suspend every other administrator and keep the cooperative to itself.
        as(alphaAdminToken, put("/api/admin/user/update"),
                Map.of("userId", alpha.admin().id(),
                        "firstName", "Ada",
                        "role", "ROLE_MEMBER",
                        "status", "SUSPENDED"))
                .andExpect(status().is4xxClientError());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT role, status FROM users WHERE id = ?", alpha.admin().id());
        assertThat(row.get("role")).isEqualTo("ROLE_ADMIN");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
    }

    // ==================================================================================== helpers

    private void assertOneRowAndNotBetas(String uri, long betasId) throws Exception {
        as(alphaAdminToken, get(uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[?(@.id == " + betasId + ")]").doesNotExist());
    }

    private String body(String bearerToken, MockHttpServletRequestBuilder request, Object payload)
            throws Exception {
        if (bearerToken != null) {
            request = request.header("Authorization", bearerToken);
        }
        if (payload != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json(payload));
        }
        return mockMvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /** A signup that will succeed: a fresh address inside Alpha, with the required fields. */
    private Map<String, Object> signupBody() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("organization", ALPHA_SLUG);
        request.put("firstName", "Chidi");
        request.put("lastName", "Okonkwo");
        request.put("gender", "male");
        request.put("email", "newcomer@shared.test");
        request.put("phone", "08019990001");
        request.put("address", "12 Marina Road");
        request.put("savingPlan", new BigDecimal("5000.00"));
        return request;
    }
}
