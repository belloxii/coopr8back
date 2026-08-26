package com.invo.coopr8.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.invo.coopr8.config.JwtConstant;
import com.invo.coopr8.support.AbstractTwoTenantTest;
import com.invo.coopr8.support.TenantFixture;

/**
 * Authentication as a tenant-scoped operation: which cooperative a sign-in is for, how that is
 * decided, and what happens when the answer is not exactly one.
 *
 * <p><strong>The property under test is that no lookup is ever global.</strong> The old login
 * path found a member by membership number alone and took whatever row came back, so
 * {@code CBMC0001} in one cooperative and {@code CBMC0001} in another were one account as far as
 * the platform was concerned. Every test here is a way of asking whether a membership number,
 * email address, payroll number or reset code can still reach across a tenant boundary.
 *
 * <p><strong>Refusals are indistinguishable on purpose.</strong> "No such cooperative", "no such
 * member" and "wrong password" all answer {@code 419} with one message, so this suite asserts
 * <em>that no token was issued</em> rather than which branch refused it -- asserting the branch
 * would lock in the oracle the single message exists to prevent.
 *
 * <p><strong>Two tests assert against the schema rather than an endpoint.</strong> D1 (globally
 * unique, letters-only ledger prefix) and D3 (email unique per cooperative, not per platform) are
 * database constraints; the only honest way to test a constraint is to violate it. They also
 * serve as proof that V2 actually applied to the database the rest of the suite runs against --
 * Hibernate's {@code validate} does not check indexes or CHECK constraints and would not notice
 * their absence.
 */
class TenantAwareAuthenticationTest extends AbstractTwoTenantTest {

    /** Satisfies the application's own strength rule, so a rejection means something else. */
    private static final String NEW_PASSWORD = "NewPassw0rd!";

    /** One sign-in attempt, labelled so a failure names it. */
    private record Attempt(String label, String organization, String ledgerID) {
    }

    // ======================================================= 1. a membership number is not global

    @Test
    @DisplayName("A membership number is only valid inside its own cooperative")
    void aMembershipNumberIsOnlyValidInsideItsOwnCooperative() throws Exception {
        // Every one of these presents a real membership number and the correct password for it.
        // The only thing wrong is the cooperative -- which is the whole of the defence.
        assertNoneIssuedAToken(List.of(
                new Attempt("Alpha's slug, Beta's member", ALPHA_SLUG, beta.member().ledgerID()),
                new Attempt("Beta's slug, Alpha's member", BETA_SLUG, alpha.member().ledgerID()),
                new Attempt("Alpha's slug, Beta's administrator", ALPHA_SLUG,
                        beta.admin().ledgerID()),
                new Attempt("a cooperative that does not exist", "ghost-coop",
                        alpha.member().ledgerID())));

        // Positive control: the same credentials at the right door.
        JsonNode accepted = login(ALPHA_SLUG, alpha.member().ledgerID(), TenantFixture.PASSWORD);
        assertThat(accepted.path("responseCode").asText()).isEqualTo("100");

        as(bearer(accepted), get("/api/auth/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.ledgerID").value(alpha.member().ledgerID()));
    }

    @Test
    @DisplayName("An explicitly named cooperative is never widened by the prefix fallback")
    void anExplicitlyNamedCooperativeIsNeverWidenedByThePrefixFallback() throws Exception {
        // ALPHA0001's prefix says Alpha. Naming Beta must fail, not fall through to what the
        // prefix would have resolved -- otherwise naming a cooperative becomes a way to search
        // a different one, which is worse than not naming one at all.
        assertNoneIssuedAToken(List.of(
                new Attempt("Beta named, Alpha's prefix in the number", BETA_SLUG,
                        alpha.member().ledgerID()),
                new Attempt("an unknown cooperative named, Alpha's prefix in the number",
                        "ghost-coop", alpha.member().ledgerID()),
                new Attempt("an unknown cooperative named, Beta's prefix in the number",
                        "ghost-coop", beta.member().ledgerID())));

        // ...and the contrast that gives those refusals their meaning: with no cooperative named
        // at all, the very same membership number resolves and signs in.
        JsonNode viaPrefix = login(null, alpha.member().ledgerID(), TenantFixture.PASSWORD);
        assertThat(viaPrefix.path("jwt").asText(""))
                .as("the fallback must still work, or the refusals above prove nothing about "
                        + "precedence -- only that the number was unusable")
                .isNotBlank();
    }

    @Test
    @DisplayName("The prefix fallback resolves the one cooperative that claims the prefix")
    void thePrefixFallbackResolvesTheOneCooperativeThatClaimsThePrefix() throws Exception {
        // This is the CBMC backward-compatibility path: a member types a bare membership number
        // at a tenant-less URL. It may resolve a tenant, but only from the prefix, and only when
        // exactly one active cooperative claims it -- never by looking the member up globally.
        String alphaToken = bearer(login(null, alpha.member().ledgerID(), TenantFixture.PASSWORD));
        String betaToken = bearer(login("", beta.member().ledgerID(), TenantFixture.PASSWORD));

        as(alphaToken, get("/api/organization/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(ALPHA_SLUG))
                .andExpect(jsonPath("$.ledgerPrefix").value(ALPHA_PREFIX));

        // A blank slug is treated as absent, not as a slug that failed to match.
        as(betaToken, get("/api/organization/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(BETA_SLUG))
                .andExpect(jsonPath("$.ledgerPrefix").value(BETA_PREFIX));

        // The tenant in each token is the one the prefix named, and each token reaches only its
        // own cooperative's member.
        as(alphaToken, get("/api/auth/profile"))
                .andExpect(jsonPath("$.user.ledgerID").value(alpha.member().ledgerID()));
        as(alphaToken, get("/api/user/id/{id}", beta.member().id()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A malformed membership number resolves no cooperative at all")
    void aMalformedMembershipNumberResolvesNoCooperativeAtAll() throws Exception {
        // With no slug supplied, the only tenant discriminator is the shape of the number. It
        // must fail closed for anything that is not LETTERS+DIGITS naming a prefix exactly one
        // active cooperative claims.
        assertNoneIssuedAToken(List.of(
                new Attempt("digits only", null, "0001"),
                new Attempt("letters only", null, "ALPHA"),
                new Attempt("a separator between the two", null, "ALPHA-0001"),
                new Attempt("trailing letters", null, "ALPHA0001X"),
                new Attempt("embedded whitespace", null, "ALPHA 0001"),
                new Attempt("a prefix no cooperative claims", null, "GAMMA0001"),
                new Attempt("a partial prefix", null, "AL0001"),
                new Attempt("a SQL wildcard", null, "%0001"),
                new Attempt("a regular expression", null, ".*0001"),
                new Attempt("a statement terminator", null, "ALPHA0001'; DROP TABLE users;--")));

        assertThat(countWhere("users"))
                .as("and nothing in that list may have altered the member table")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("A suspended cooperative has no front door, and its existing tokens stop working")
    void aSuspendedCooperativeHasNoFrontDoorAndItsExistingTokensStopWorking() throws Exception {
        // Beta's member is holding a token that was valid a moment ago.
        as(betaMemberToken, get("/api/organization/current")).andExpect(status().isOk());

        jdbcTemplate.update("UPDATE organizations SET status = 'SUSPENDED' WHERE id = ?",
                beta.organizationId());

        // A signature stays valid until the token expires, so the organization is re-resolved and
        // re-checked on every request. Without that, suspension would take hours to take effect.
        as(betaMemberToken, get("/api/organization/current"))
                .andExpect(status().isUnauthorized());
        as(betaAdminToken, get("/api/admin/users"))
                .andExpect(status().isUnauthorized());

        assertNoneIssuedAToken(List.of(
                new Attempt("by slug", BETA_SLUG, beta.member().ledgerID()),
                new Attempt("by ledger prefix", null, beta.member().ledgerID())));

        // Nor may the branding endpoint confirm that the cooperative exists: an unknown slug and
        // a suspended one answer the same 404.
        mockMvc.perform(get("/api/organization/public/{slug}", BETA_SLUG))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organization/public/{slug}", "ghost-coop"))
                .andExpect(status().isNotFound());

        // Suspending one tenant must not disturb another.
        as(alphaMemberToken, get("/api/organization/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(ALPHA_SLUG));
        mockMvc.perform(get("/api/organization/public/{slug}", ALPHA_SLUG))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An unauthenticated or tampered request reaches no protected endpoint")
    void anUnauthenticatedOrTamperedRequestReachesNoProtectedEndpoint() throws Exception {
        Map<String, Integer> notUnauthorized = new LinkedHashMap<>();

        for (String uri : List.of(
                "/api/organization/current",
                "/api/auth/profile",
                "/api/user/id/" + alpha.member().id(),
                "/api/loan/myloans",
                "/api/savings/mysavings",
                "/api/notis/mynotis",
                "/api/admin/users")) {

            record(notUnauthorized, "no token: " + uri,
                    mockMvc.perform(get(uri)).andReturn().getResponse().getStatus());
            record(notUnauthorized, "junk token: " + uri,
                    as(JwtConstant.BEARER_PREFIX + "not-a-token", get(uri))
                            .andReturn().getResponse().getStatus());
            record(notUnauthorized, "tampered signature: " + uri,
                    as(withBrokenSignature(alphaMemberToken), get(uri))
                            .andReturn().getResponse().getStatus());
        }

        assertThat(notUnauthorized)
                .as("no token, an unparseable token and a token whose signature was altered must "
                        + "all be 401 -- a tampered token that merely fails a later check would "
                        + "mean the signature is not what is trusted")
                .isEmpty();
    }

    @Test
    @DisplayName("The cooperative in the token wins over anything the caller supplies")
    void theCooperativeInTheTokenWinsOverAnythingTheCallerSupplies() throws Exception {
        // Once authenticated, the tenant comes from the verified token and nothing else. A query
        // parameter, a header and a request body are all caller-controlled.
        as(alphaMemberToken, get("/api/organization/current")
                .param("organization", BETA_SLUG)
                .param("organizationId", String.valueOf(beta.organizationId()))
                .header("X-Organization", BETA_SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(ALPHA_SLUG));

        // Signup is the one flow that reads a slug from the body -- but only when nobody is
        // authenticated. An administrator onboarding a member creates them inside their own
        // cooperative, whatever the body says, or an admin could plant members elsewhere.
        Map<String, Object> signup = new LinkedHashMap<>();
        signup.put("organization", BETA_SLUG);
        signup.put("firstName", "Chidi");
        signup.put("lastName", "Okonkwo");
        signup.put("gender", "male");
        signup.put("email", "newcomer@shared.test");
        signup.put("phone", "08019990001");
        signup.put("address", "12 Marina Road");
        signup.put("savingPlan", new BigDecimal("5000.00"));

        as(alphaAdminToken, post("/api/auth/signup"), signup).andExpect(status().isOk());

        assertThat(countWhere("users WHERE organization_id = ? AND lower(email) = ?",
                alpha.organizationId(), "newcomer@shared.test"))
                .as("the new member belongs to the administrator's own cooperative")
                .isEqualTo(1);
        assertThat(countWhere("users WHERE organization_id = ? AND lower(email) = ?",
                beta.organizationId(), "newcomer@shared.test"))
                .as("and not to the one named in the body")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ledgerid FROM users WHERE lower(email) = ?", String.class,
                "newcomer@shared.test"))
                .as("their membership number carries their own cooperative's prefix")
                .startsWith(ALPHA_PREFIX);
    }

    // ================================================== 2. the schema enforces D1 and D3 itself

    @Test
    @DisplayName("The schema enforces a globally unique, letters-only ledger prefix (D1)")
    void theSchemaEnforcesAGloballyUniqueLettersOnlyLedgerPrefix() throws Exception {
        // The prefix is a tenant discriminator at login, so two cooperatives claiming one prefix
        // would make the fallback ambiguous -- and "ambiguous" resolved either way is a
        // cross-tenant sign-in. The database, not the service, is what makes that impossible.
        assertRejected("the same prefix again", "impostor-coop", "ALPHA");
        assertRejected("the same prefix in lower case", "impostor-coop", "alpha");
        assertRejected("the same prefix in mixed case", "impostor-coop", "Alpha");
        assertRejected("a prefix containing a digit", "impostor-coop", "GAMMA1");
        assertRejected("a prefix containing a space", "impostor-coop", "GA MMA");
        assertRejected("an empty prefix", "impostor-coop", "");
        // V1's UNIQUE (slug) is case-sensitive; the case-insensitive index is what stops
        // /o/ALPHA-COOP and /o/alpha-coop being two tenants.
        assertRejected("an existing slug in different case", "ALPHA-COOP", "GAMMA");

        // Control: a genuinely new cooperative inserts cleanly, so the rejections above are
        // about the values and not about the statement.
        insertOrganization("gamma-coop", "GAMMA");
        assertThat(countWhere("organizations")).isEqualTo(3);
    }

    @Test
    @DisplayName("The schema enforces email uniqueness per cooperative, not per platform (D3)")
    void theSchemaEnforcesEmailUniquenessPerCooperativeNotPerPlatform() throws Exception {
        // D3: one person may be a member of several cooperatives with one address. The fixture
        // relies on it, and the reset flows below would prove nothing without it.
        assertThat(countWhere("users WHERE lower(email) = ?", TenantFixture.SHARED_MEMBER_EMAIL))
                .as("the same address is already a member of both cooperatives")
                .isEqualTo(2);

        // A third cooperative may have that member too.
        long gammaId = insertOrganization("gamma-coop", "GAMMA");
        insertMember(gammaId, "GAMMA0001", TenantFixture.SHARED_MEMBER_EMAIL);
        assertThat(countWhere("users WHERE lower(email) = ?", TenantFixture.SHARED_MEMBER_EMAIL))
                .isEqualTo(3);

        // What must not happen is two records for that person inside one cooperative -- which is
        // what would make a tenant-scoped lookup ambiguous, and a reset code deliverable to
        // either of two accounts.
        assertThatThrownBy(() -> insertMember(alpha.organizationId(), "ALPHA0002",
                TenantFixture.SHARED_MEMBER_EMAIL))
                .as("a duplicate address inside one cooperative must be refused")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertMember(alpha.organizationId(), "ALPHA0003",
                TenantFixture.SHARED_MEMBER_EMAIL.toUpperCase()))
                .as("and case is not a way around it -- the index is on lower(email)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ============================================================ 3. one-time codes are per-tenant

    @Test
    @DisplayName("A reset code is issued inside the named cooperative only")
    void aResetCodeIsIssuedInsideTheNamedCooperativeOnly() throws Exception {
        JsonNode issued = postJson("/api/otp/sendOTP",
                new OtpBody(ALPHA_SLUG, TenantFixture.SHARED_MEMBER_EMAIL, null, "forgetpass"));
        assertThat(issued.path("responseCode").asText()).isEqualTo("100");

        // The address belongs to a member of both cooperatives, so the tenant column is the only
        // thing that distinguishes whose code this is. Before Phase 2 the otp table had no such
        // column at all: a code was keyed on the email address and nothing else.
        assertThat(countWhere("otp")).isEqualTo(1);
        assertThat(countWhere(
                "otp WHERE organization_id = ? AND purpose = 'PASSWORD_RESET' AND lower(email) = ?",
                alpha.organizationId(), TenantFixture.SHARED_MEMBER_EMAIL))
                .as("the code belongs to Alpha's member, not to the address in general")
                .isEqualTo(1);

        assertThat(recordedEmails.lastSent().recipient())
                .as("the code goes to the address on the member's record")
                .isEqualTo(TenantFixture.SHARED_MEMBER_EMAIL);
        assertThat(recordedEmails.lastSent().senderName())
                .as("and is branded as the cooperative that issued it")
                .contains(ALPHA_SLUG);
        assertThat(recordedEmails.lastSent().mentions(beta.slug()))
                .as("Tenant A's mail must never name Tenant B")
                .isFalse();

        // Beta may hold a live code for the same address at the same time.
        postJson("/api/otp/sendOTP",
                new OtpBody(BETA_SLUG, TenantFixture.SHARED_MEMBER_EMAIL, null, "forgetpass"));

        assertThat(countWhere("otp")).isEqualTo(2);
        assertThat(countWhere("otp WHERE organization_id = ?", alpha.organizationId())).isEqualTo(1);
        assertThat(countWhere("otp WHERE organization_id = ?", beta.organizationId())).isEqualTo(1);
    }

    @Test
    @DisplayName("A reset code from another cooperative does not validate")
    void aResetCodeFromAnotherCooperativeDoesNotValidate() throws Exception {
        postJson("/api/otp/sendOTP",
                new OtpBody(ALPHA_SLUG, TenantFixture.SHARED_MEMBER_EMAIL, null, "forgetpass"));
        String alphasCode = resetCodeFor(alpha.organizationId());

        assertThat(postJson("/api/otp/validateOTP",
                new OtpBody(BETA_SLUG, TenantFixture.SHARED_MEMBER_EMAIL, alphasCode, "forgetpass"))
                .path("responseCode").asText())
                .as("Alpha's code presented at Beta, for an address that is a member of both")
                .isEqualTo("419");

        // Control: the same code at the cooperative that issued it.
        assertThat(postJson("/api/otp/validateOTP",
                new OtpBody(ALPHA_SLUG, TenantFixture.SHARED_MEMBER_EMAIL, alphasCode, "forgetpass"))
                .path("responseCode").asText())
                .isEqualTo("100");
    }

    @Test
    @DisplayName("A change-password code follows the token, not the request body")
    void aChangePasswordCodeFollowsTheTokenNotTheRequestBody() throws Exception {
        // An authenticated member's tenant and address both come from their record. The body here
        // names another cooperative and an attacker-controlled address; both must be ignored.
        JsonNode issued = asJson(alphaMemberToken, post("/api/otp/sendOTP"),
                new OtpBody(BETA_SLUG, "attacker@elsewhere.test", null, "changepass"));
        assertThat(issued.path("responseCode").asText()).isEqualTo("100");

        assertThat(countWhere("otp")).isEqualTo(1);
        assertThat(countWhere(
                "otp WHERE organization_id = ? AND purpose = 'PASSWORD_CHANGE' AND lower(email) = ?",
                alpha.organizationId(), TenantFixture.SHARED_MEMBER_EMAIL))
                .as("the code is Alpha's, for Alpha's member's own address")
                .isEqualTo(1);
        assertThat(countWhere("otp WHERE lower(email) = ?", "attacker@elsewhere.test"))
                .as("a code deliverable to an address of the caller's choosing is an account "
                        + "takeover, not a password change")
                .isZero();
        assertThat(recordedEmails.lastSent().recipient())
                .isEqualTo(TenantFixture.SHARED_MEMBER_EMAIL);
    }

    @Test
    @DisplayName("A request that names no cooperative fails closed")
    void aRequestThatNamesNoCooperativeFailsClosed() throws Exception {
        // No token, no slug, and no onboarding fallback configured for tests. There is no correct
        // tenant to guess, so the answer is an error -- never "the only organization", never
        // Citadel, never COOPR8.
        Map<String, Integer> statuses = new LinkedHashMap<>();
        statuses.put("sendOTP, no cooperative", statusOf("/api/otp/sendOTP",
                new OtpBody(null, TenantFixture.SHARED_MEMBER_EMAIL, null, "forgetpass")));
        statuses.put("validateOTP, no cooperative", statusOf("/api/otp/validateOTP",
                new OtpBody(null, TenantFixture.SHARED_MEMBER_EMAIL, "not-a-code", "forgetpass")));
        statuses.put("forgot-password/request, no cooperative",
                statusOf("/api/auth/forgot-password/request",
                        new ForgotBody(null, alpha.member().ledgerID(), null)));
        statuses.put("forgot-password/reset, no cooperative",
                statusOf("/api/auth/forgot-password/reset",
                        new ResetBody(null, alpha.member().ledgerID(), null, "not-a-code",
                                NEW_PASSWORD, NEW_PASSWORD)));

        assertThat(statuses.values())
                .as("an unresolvable tenant is a 409, not a guess: %s", statuses)
                .containsOnly(409);

        assertThat(statusOf("/api/otp/sendOTP",
                new OtpBody("ghost-coop", TenantFixture.SHARED_MEMBER_EMAIL, null, "forgetpass")))
                .as("and an unknown slug is a 404")
                .isEqualTo(404);

        assertThat(countWhere("otp"))
                .as("no code may have been issued by any of those")
                .isZero();
    }

    // ================================================ 4. forgotten passwords stay inside a tenant

    @Test
    @DisplayName("A reset code from one cooperative cannot reset an account in another")
    void aResetCodeFromOneCooperativeCannotResetAnAccountInAnother() throws Exception {
        // This is the mandate's explicit requirement, and the reason the fixture's two members
        // share an email address: with distinct addresses the replay below would fail because the
        // member could not be found, which proves nothing about the code.
        postJson("/api/auth/forgot-password/request",
                new ForgotBody(ALPHA_SLUG, alpha.member().ledgerID(), null));
        String alphasCode = resetCodeFor(alpha.organizationId());

        List<ResetBody> replays = List.of(
                new ResetBody(BETA_SLUG, beta.member().ledgerID(), null, alphasCode,
                        NEW_PASSWORD, NEW_PASSWORD),
                new ResetBody(BETA_SLUG, null, TenantFixture.SHARED_MEMBER_EMAIL, alphasCode,
                        NEW_PASSWORD, NEW_PASSWORD),
                new ResetBody(BETA_SLUG, beta.admin().ledgerID(), null, alphasCode,
                        NEW_PASSWORD, NEW_PASSWORD));

        for (ResetBody replay : replays) {
            assertThat(postJson("/api/auth/forgot-password/reset", replay)
                    .path("responseCode").asText())
                    .as("Alpha's code must not reset anything at Beta: %s", replay)
                    .isEqualTo("419");
        }

        // Nothing was changed on the way to being refused.
        assertPasswordUnchanged(beta.member().id(), "Beta's member");
        assertPasswordUnchanged(beta.admin().id(), "Beta's administrator");
        assertPasswordUnchanged(alpha.member().id(), "Alpha's member, whose code it was");

        assertThat(countWhere("otp WHERE organization_id = ?", alpha.organizationId()))
                .as("a failed replay elsewhere must not consume the code either")
                .isEqualTo(1);

        // And Beta's member can still sign in with the password they have always had.
        assertThat(login(BETA_SLUG, beta.member().ledgerID(), TenantFixture.PASSWORD)
                .path("jwt").asText("")).isNotBlank();
    }

    @Test
    @DisplayName("The reset flow changes only the right member's password")
    void theResetFlowChangesOnlyTheRightMembersPassword() throws Exception {
        // Requested by email on purpose: the shared address is the case where an unscoped lookup
        // would pick a member of the wrong cooperative.
        postJson("/api/auth/forgot-password/request",
                new ForgotBody(ALPHA_SLUG, null, TenantFixture.SHARED_MEMBER_EMAIL));
        String code = resetCodeFor(alpha.organizationId());

        assertThat(postJson("/api/auth/forgot-password/reset",
                new ResetBody(ALPHA_SLUG, null, TenantFixture.SHARED_MEMBER_EMAIL, code,
                        NEW_PASSWORD, NEW_PASSWORD))
                .path("responseCode").asText())
                .isEqualTo("100");

        assertThat(passwordEncoder.matches(NEW_PASSWORD, storedHash(alpha.member().id())))
                .as("Alpha's member now has the password they chose")
                .isTrue();
        assertPasswordUnchanged(beta.member().id(),
                "Beta's member, who shares the address the reset was requested with");

        // Through the front door: the new password works, the old one does not, and Beta is
        // entirely unaffected.
        assertThat(login(ALPHA_SLUG, alpha.member().ledgerID(), NEW_PASSWORD)
                .path("jwt").asText("")).isNotBlank();
        assertThat(login(ALPHA_SLUG, alpha.member().ledgerID(), TenantFixture.PASSWORD)
                .path("jwt").asText(""))
                .as("the old password must be dead")
                .isBlank();
        assertThat(login(BETA_SLUG, beta.member().ledgerID(), TenantFixture.PASSWORD)
                .path("jwt").asText("")).isNotBlank();

        // The code is single-use: consumed on success, so a leaked email cannot be replayed.
        assertThat(countWhere("otp")).isZero();
        assertThat(postJson("/api/auth/forgot-password/reset",
                new ResetBody(ALPHA_SLUG, null, TenantFixture.SHARED_MEMBER_EMAIL, code,
                        "Another-Passw0rd!", "Another-Passw0rd!"))
                .path("responseCode").asText())
                .isEqualTo("419");
        assertThat(passwordEncoder.matches(NEW_PASSWORD, storedHash(alpha.member().id())))
                .as("and the replay changed nothing")
                .isTrue();

        assertThat(recordedEmails.sent().stream().filter(mail -> mail.mentions(beta.slug())).toList())
                .as("no mail sent during Alpha's reset may name Beta")
                .isEmpty();
    }

    @Test
    @DisplayName("The reset endpoints do not reveal who is a member")
    void theResetEndpointsDoNotRevealWhoIsAMember() throws Exception {
        // An unauthenticated endpoint that answers differently for a member and a stranger is a
        // membership oracle: "does this person belong to this cooperative?", free, for any address
        // or number someone cares to try. All three of these must be indistinguishable.
        String forAMember = messageOf("/api/auth/forgot-password/request",
                new ForgotBody(ALPHA_SLUG, alpha.member().ledgerID(), null));
        String forAStranger = messageOf("/api/auth/forgot-password/request",
                new ForgotBody(ALPHA_SLUG, "ALPHA9999", null));
        String forAnotherTenantsMember = messageOf("/api/auth/forgot-password/request",
                new ForgotBody(ALPHA_SLUG, beta.member().ledgerID(), null));

        assertThat(forAStranger).isEqualTo(forAMember);
        assertThat(forAnotherTenantsMember).isEqualTo(forAMember);

        assertThat(countWhere("otp"))
                .as("only the one real member may actually have been sent a code")
                .isEqualTo(1);
        assertThat(countWhere("otp WHERE organization_id = ?", alpha.organizationId())).isEqualTo(1);

        // The same for redemption: a wrong code, an unknown member and another cooperative's
        // member all answer identically.
        String wrongCode = messageOf("/api/auth/forgot-password/reset",
                new ResetBody(ALPHA_SLUG, alpha.member().ledgerID(), null, "not-a-code",
                        NEW_PASSWORD, NEW_PASSWORD));
        String unknownMember = messageOf("/api/auth/forgot-password/reset",
                new ResetBody(ALPHA_SLUG, "ALPHA9999", null, resetCodeFor(alpha.organizationId()),
                        NEW_PASSWORD, NEW_PASSWORD));
        String otherTenantsMember = messageOf("/api/auth/forgot-password/reset",
                new ResetBody(ALPHA_SLUG, beta.member().ledgerID(), null,
                        resetCodeFor(alpha.organizationId()), NEW_PASSWORD, NEW_PASSWORD));

        assertThat(unknownMember).isEqualTo(wrongCode);
        assertThat(otherTenantsMember).isEqualTo(wrongCode);
    }

    // ==================================================================================== helpers

    /** Posts a sign-in and returns the parsed response. The endpoint answers 200 either way. */
    private JsonNode login(String organization, String ledgerID, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new LoginBody(organization, ledgerID, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /**
     * Asserts that not one of these attempts produced a token, naming every attempt that did.
     *
     * <p>Deliberately not asserting <em>why</em> each was refused: login answers one message for
     * every credential failure so that it cannot be used to discover which membership numbers
     * exist, and a test that pinned the reason would make that property unmaintainable.
     */
    private void assertNoneIssuedAToken(List<Attempt> attempts) throws Exception {
        Map<String, String> issued = new LinkedHashMap<>();

        for (Attempt attempt : attempts) {
            JsonNode response = login(attempt.organization(), attempt.ledgerID(),
                    TenantFixture.PASSWORD);
            String jwt = response.path("jwt").asText("");
            if (!jwt.isBlank()) {
                issued.put(attempt.label(), response.path("responseMessage").asText());
            } else {
                assertThat(response.path("responseCode").asText())
                        .as("%s: a refusal must report failure, not a success with no token",
                                attempt.label())
                        .isEqualTo("419");
            }
        }

        assertThat(issued)
                .as("each of these presents a valid membership number and the correct password "
                        + "for it, at the wrong cooperative or with no resolvable one")
                .isEmpty();
    }

    private String bearer(JsonNode loginResponse) {
        String jwt = loginResponse.path("jwt").asText("");
        assertThat(jwt).as("expected this sign-in to succeed: %s", loginResponse).isNotBlank();
        return JwtConstant.BEARER_PREFIX + jwt;
    }

    /**
     * The same token with a signature that is genuinely different, leaving the header and payload
     * intact.
     *
     * <p>Decoded, altered and re-encoded rather than edited as text. An HMAC-SHA512 signature is 64
     * bytes, which base64url-encodes to 86 characters of which the <em>last carries only two
     * significant bits</em> -- the other four are padding. Replacing that character therefore
     * decodes to the identical 64 bytes whenever the substitute shares those two bits, which for a
     * random signature is about a quarter of the time. This helper used to do exactly that, so
     * roughly one run in four asserted nothing about tampering at all: it sent the original,
     * perfectly valid token and then failed because the endpoints honoured it.
     *
     * <p>Flipping a bit inside the first byte cannot be absorbed by padding, so the tampering is
     * always real -- and the assertion below refuses to hand back a token it did not change.
     */
    private String withBrokenSignature(String bearerToken) {
        int lastDot = bearerToken.lastIndexOf('.');
        String original = bearerToken.substring(lastDot + 1);

        byte[] signature = Base64.getUrlDecoder().decode(original);
        signature[0] ^= 0x01;
        String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        assertThat(tampered)
                .as("a tampered token whose signature still decodes to the original bytes would "
                        + "prove nothing")
                .isNotEqualTo(original);

        return bearerToken.substring(0, lastDot + 1) + tampered;
    }

    private static void record(Map<String, Integer> failures, String label, int status) {
        if (status != 401) {
            failures.put(label, status);
        }
    }

    private long insertOrganization(String slug, String ledgerPrefix) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO organizations
                    (name, legal_name, slug, ledger_prefix, status, email, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, now(), now())
                RETURNING id
                """, Long.class,
                slug + " Cooperative Society", slug + " Cooperative Society Limited",
                slug, ledgerPrefix, "secretary@" + slug + ".test");
        return id == null ? 0L : id;
    }

    private void assertRejected(String because, String slug, String ledgerPrefix) {
        assertThatThrownBy(() -> insertOrganization(slug, ledgerPrefix))
                .as("the schema must reject %s", because)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertMember(long organizationId, String ledgerID, String email) {
        jdbcTemplate.update("""
                INSERT INTO users
                    (organization_id, ledgerid, ledger_number, first_name, email, password,
                     status, role, payment_type, created_at)
                VALUES (?, ?, 1, 'Extra', ?, 'x', 'ACTIVE', 'ROLE_MEMBER', 'SELF_PAY', now())
                """, organizationId, ledgerID, email);
    }

    // ------------------------------------------------------------------ OTP and reset plumbing

    /** Posts an unauthenticated JSON body to an endpoint that answers 200 with a result code. */
    private JsonNode postJson(String uri, Object body) throws Exception {
        String response = mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** The same, as the holder of a token. */
    private JsonNode asJson(String bearerToken, MockHttpServletRequestBuilder request, Object body)
            throws Exception {
        String response = as(bearerToken, request, body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    /** HTTP status only, for the paths that fail with a status rather than a result code. */
    private int statusOf(String uri, Object body) throws Exception {
        return mockMvc.perform(post(uri)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andReturn().getResponse().getStatus();
    }

    private String messageOf(String uri, Object body) throws Exception {
        return postJson(uri, body).path("responseMessage").asText();
    }

    /** The live reset code for one cooperative, read straight out of the table. */
    private String resetCodeFor(long organizationId) {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT otp FROM otp WHERE organization_id = ? AND purpose = 'PASSWORD_RESET'",
                String.class, organizationId);

        assertThat(codes)
                .as("exactly one live reset code must exist for organization %s -- issuing a "
                        + "second without superseding the first would leave two valid credentials",
                        organizationId)
                .hasSize(1);
        return codes.get(0);
    }

    private String storedHash(long userId) {
        return jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?",
                String.class, userId);
    }

    private void assertPasswordUnchanged(long userId, String who) {
        assertThat(passwordEncoder.matches(TenantFixture.PASSWORD, storedHash(userId)))
                .as("%s must still have the password they started with", who)
                .isTrue();
    }

    /** Mirrors {@code OTPRequest}'s JSON shape. */
    private record OtpBody(String organization, String email, String otp, String action) {
    }

    /** Mirrors {@code ForgotPasswordRequest}. */
    private record ForgotBody(String organization, String ledgerID, String email) {
    }

    /** Mirrors {@code ResetPasswordRequest}. */
    private record ResetBody(String organization, String ledgerID, String email, String otp,
            String newPassword, String confirmPassword) {
    }
}
