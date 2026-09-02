package com.invo.coopr8.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.model.InterestMethod;
import com.invo.coopr8.model.OrganizationMembershipConfig;
import com.invo.coopr8.model.SavingsFrequency;

/**
 * The refusals the configuration endpoints share, tested without a database.
 *
 * <p>These are the rules that cannot be expressed as an annotation on a DTO -- membership of a set
 * defined in Java, and relationships between two fields. Each one exists because the alternative is a
 * cooperative storing a rule the platform will not honour.
 */
class ConfigRulesTest {

    // ------------------------------------------------------------------ interest method

    @ParameterizedTest
    @ValueSource(strings = { "NONE", "none", "  Flat  ", "FLAT" })
    @DisplayName("The two implemented interest methods are accepted, in any casing")
    void selectableMethodsAreAccepted(String submitted) {
        assertThat(ConfigRules.requireSelectableInterestMethod(submitted))
                .isIn(InterestMethod.NONE, InterestMethod.FLAT);
    }

    @Test
    @DisplayName("REDUCING_BALANCE is refused, because nothing computes it")
    void reducingBalanceIsRefused() {
        // The enum has it so a future implementation has a name to persist. Offering it on a
        // settings screen would let a cooperative select an interest method the platform silently
        // ignores -- members would then be charged something other than what was agreed.
        assertThat(InterestMethod.REDUCING_BALANCE.isAdminSelectable())
                .as("if this ever becomes true, this test is the wrong test rather than a failing one")
                .isFalse();

        assertThatThrownBy(
                () -> ConfigRules.requireSelectableInterestMethod("REDUCING_BALANCE"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(refusal -> assertThat(
                        ((ResponseStatusException) refusal).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("REDUCING_BALANCE")
                .hasMessageContaining("not available yet");
    }

    @Test
    @DisplayName("An unknown interest method is refused and the message says what is available")
    void unknownMethodIsRefused() {
        assertThatThrownBy(() -> ConfigRules.requireSelectableInterestMethod("COMPOUND"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("COMPOUND")
                .hasMessageContaining("NONE")
                .hasMessageContaining("FLAT")
                // The message must not advertise a method nobody can choose.
                .hasMessageNotContaining("REDUCING_BALANCE");
    }

    @Test
    @DisplayName("A loan product may leave the interest method empty, meaning inherit")
    void blankMethodMeansInherit() {
        assertThat(ConfigRules.optionalSelectableInterestMethod(null)).isNull();
        assertThat(ConfigRules.optionalSelectableInterestMethod("   ")).isNull();
        assertThat(ConfigRules.optionalSelectableInterestMethod("FLAT"))
                .isEqualTo(InterestMethod.FLAT);
    }

    // ------------------------------------------------------------------ frequency

    @Test
    @DisplayName("Every declared savings frequency is accepted")
    void everyFrequencyIsAccepted() {
        for (SavingsFrequency frequency : SavingsFrequency.values()) {
            assertThat(ConfigRules.requireFrequency(frequency.name().toLowerCase()))
                    .isEqualTo(frequency);
        }
    }

    @Test
    void unknownFrequencyIsRefused() {
        assertThatThrownBy(() -> ConfigRules.requireFrequency("FORTNIGHTLY"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("FORTNIGHTLY")
                .hasMessageContaining("MONTHLY");
    }

    // ------------------------------------------------------------------ member status

    @Test
    @DisplayName("Only the three statuses the platform can act on are accepted")
    void onlyKnownMemberStatusesAreAccepted() {
        assertThat(ConfigRules.requireMemberStatus("pending"))
                .isEqualTo(OrganizationMembershipConfig.STATUS_PENDING);
        assertThat(ConfigRules.requireMemberStatus(" NEW "))
                .isEqualTo(OrganizationMembershipConfig.STATUS_NEW);
        assertThat(ConfigRules.requireMemberStatus("ACTIVE"))
                .isEqualTo(OrganizationMembershipConfig.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("A free-form status is refused, because no code path could unlock such a member")
    void unknownMemberStatusIsRefused() {
        assertThatThrownBy(() -> ConfigRules.requireMemberStatus("AWAITING_DOCS"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("AWAITING_DOCS")
                .hasMessageContaining("PENDING");
    }

    // ------------------------------------------------------------------ ordering

    @Test
    @DisplayName("A minimum above its maximum is refused")
    void invertedBoundsAreRefused() {
        assertThatThrownBy(() -> ConfigRules.requireOrderedAmounts(
                new BigDecimal("500000"), new BigDecimal("100000"), "loan amount"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("loan amount");

        assertThatThrownBy(() -> ConfigRules.requireOrderedMonths(24, 12, "tenure"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("tenure");
    }

    @Test
    @DisplayName("A single bound is allowed: null means no bound, not zero")
    void oneSidedBoundsAreAllowed() {
        ConfigRules.requireOrderedAmounts(new BigDecimal("100000"), null, "loan amount");
        ConfigRules.requireOrderedAmounts(null, new BigDecimal("100000"), "loan amount");
        ConfigRules.requireOrderedAmounts(null, null, "loan amount");
        ConfigRules.requireOrderedMonths(null, 12, "tenure");
        ConfigRules.requireOrderedMonths(12, null, "tenure");
    }

    @Test
    @DisplayName("Equal bounds are allowed: \"exactly this much\" is a coherent rule")
    void equalBoundsAreAllowed() {
        ConfigRules.requireOrderedAmounts(new BigDecimal("50000.00"), new BigDecimal("50000"),
                "loan amount");
        ConfigRules.requireOrderedMonths(12, 12, "tenure");
    }

    // ------------------------------------------------------------------ names

    @Test
    @DisplayName("A loan product name is normalized the way its unique index normalizes it")
    void loanProductNamesCollapseInteriorWhitespace() {
        // ux_organization_loan_type_org_name is unique on
        // lower(btrim(regexp_replace(name, '[[:space:]]+', ' ', 'g'))). Storing the un-collapsed
        // form would let the application's duplicate check pass where the index refuses the insert.
        assertThat(ConfigRules.normalizedProductName("  Real   Estate\tLoan  "))
                .isEqualTo("Real Estate Loan");
        assertThat(ConfigRules.normalizedProductName("Asset")).isEqualTo("Asset");
    }

    @Test
    @DisplayName("A savings plan name is only trimmed, matching its own index")
    void savingsPlanNamesAreOnlyTrimmed() {
        // ux_organization_savings_plan_org_name is unique on lower(btrim(name)) -- trimmed, not
        // interior-collapsed. The two normalizations differ because the two indexes differ.
        assertThat(ConfigRules.normalizedPlanName("  Monthly   Target  "))
                .isEqualTo("Monthly   Target");
    }

    // ------------------------------------------------------------------ statuses

    @Test
    @DisplayName("Not-yours and absent are the same 404")
    void notFoundIsA404WithNoDetail() {
        ResponseStatusException notFound = ConfigRules.notFound();
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getReason()).isEqualTo("Not found.");
    }

    @Test
    void conflictIsA409() {
        assertThat(ConfigRules.conflict("taken").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void refusalIsA400() {
        assertThat(ConfigRules.refusal("no").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
