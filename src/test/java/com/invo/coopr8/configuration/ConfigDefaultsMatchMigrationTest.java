package com.invo.coopr8.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link ConfigProvisioner}'s defaults are the same values {@code V9} seeded.
 *
 * <h2>Why this test exists</h2>
 * The neutral defaults are written down twice, and they have to be: {@code V9} gave them to every
 * cooperative that existed when it ran, and {@link ConfigProvisioner} gives them to every cooperative
 * onboarded afterwards. Two copies of the same decision is exactly the shape that drifts -- somebody
 * revises the migration, or revises the constant, and from then on a cooperative's behaviour depends
 * on which side of a deploy it was created.
 *
 * <p>The consequence would be silent and financial. A cooperative provisioned with
 * {@code required_guarantors = 1} while its neighbours have 2 does not fail; it just quietly approves
 * loans on one signature.
 *
 * <p>This reads the migration as text, because that is what the migration is. It parses the four
 * {@code INSERT} statements' column and value lists and compares them, column by column, against the
 * constants. No database and no Docker: the migration file is the artefact under test.
 */
class ConfigDefaultsMatchMigrationTest {

    private static final Path SEED_MIGRATION = Path.of("src", "main", "resources", "db",
            "migration", "V9__phase4_seed_defaults.sql");

    /** Columns every seed sets the same way, which say nothing about a default. */
    private static final List<String> UNINTERESTING = List.of("organization_id", "created_at",
            "updated_at");

    @Test
    @DisplayName("V9 seeds the loan defaults ConfigProvisioner provisions")
    void loanDefaultsAgree() throws IOException {
        assertThat(seededValues("organization_loan_config")).isEqualTo(Map.of(
                "interest_method", "NONE",
                "interest_rate", "0.000",
                "required_guarantors", "2"));

        // The other side of the same claim, so a change to either constant fails here rather than
        // silently agreeing with a migration nobody re-read.
        assertThat(ConfigProvisioner.DEFAULT_INTEREST_METHOD.name()).isEqualTo("NONE");
        assertThat(ConfigProvisioner.DEFAULT_INTEREST_RATE.toPlainString()).isEqualTo("0.000");
        assertThat(ConfigProvisioner.DEFAULT_REQUIRED_GUARANTORS).isEqualTo(2);
    }

    @Test
    @DisplayName("V9 seeds the shares defaults ConfigProvisioner provisions")
    void sharesDefaultsAgree() throws IOException {
        assertThat(seededValues("organization_shares_config")).isEqualTo(Map.of(
                "share_price", "1.00",
                "approval_required", "true",
                "withdrawal_allowed", "true"));

        assertThat(ConfigProvisioner.DEFAULT_SHARE_PRICE.toPlainString()).isEqualTo("1.00");
        assertThat(ConfigProvisioner.DEFAULT_APPROVAL_REQUIRED).isTrue();
        assertThat(ConfigProvisioner.DEFAULT_WITHDRAWAL_ALLOWED).isTrue();
    }

    @Test
    @DisplayName("V9 seeds the repayment defaults ConfigProvisioner provisions")
    void repaymentDefaultsAgree() throws IOException {
        assertThat(seededValues("organization_repayment_config")).isEqualTo(Map.of(
                "allow_partial_repayment", "false",
                "allow_overpayment", "false",
                "settlement_tolerance", "0.00"));

        assertThat(ConfigProvisioner.DEFAULT_ALLOW_PARTIAL_REPAYMENT).isFalse();
        assertThat(ConfigProvisioner.DEFAULT_ALLOW_OVERPAYMENT).isFalse();
        assertThat(ConfigProvisioner.DEFAULT_SETTLEMENT_TOLERANCE.toPlainString())
                .isEqualTo("0.00");
    }

    @Test
    @DisplayName("V9 seeds the membership defaults ConfigProvisioner provisions")
    void membershipDefaultsAgree() throws IOException {
        Map<String, String> seeded = seededValues("organization_membership_config");

        assertThat(seeded).isEqualTo(Map.of(
                "require_email", "true",
                "require_phone", "true",
                "require_psn", "false",
                "require_passport", "false",
                "require_next_of_kin", "false",
                "auto_activate_members", "false",
                "default_member_status", "PENDING"));

        assertThat(ConfigProvisioner.DEFAULT_REQUIRE_EMAIL).isTrue();
        assertThat(ConfigProvisioner.DEFAULT_REQUIRE_PHONE).isTrue();
        assertThat(ConfigProvisioner.DEFAULT_REQUIRE_PSN).isFalse();
        assertThat(ConfigProvisioner.DEFAULT_REQUIRE_PASSPORT).isFalse();
        assertThat(ConfigProvisioner.DEFAULT_REQUIRE_NEXT_OF_KIN).isFalse();
        assertThat(ConfigProvisioner.DEFAULT_AUTO_ACTIVATE_MEMBERS).isFalse();
        assertThat(ConfigProvisioner.DEFAULT_MEMBER_STATUS).isEqualTo("PENDING");

        // The status seeded has to be one the platform can act on, or every cooperative starts with
        // members no code path can unlock. ConfigRules refuses anything else on the way in; this is
        // the same claim about the value the migration wrote.
        assertThat(ConfigProvisioner.DEFAULT_MEMBER_STATUS).isIn(
                com.invo.coopr8.model.OrganizationMembershipConfig.STATUS_NEW,
                com.invo.coopr8.model.OrganizationMembershipConfig.STATUS_PENDING,
                com.invo.coopr8.model.OrganizationMembershipConfig.STATUS_ACTIVE);
    }

    @Test
    @DisplayName("V9 configures no bound, no loan product and no savings plan")
    void v9LeavesEverythingElseUnset() throws IOException {
        String migration = Files.readString(SEED_MIGRATION, StandardCharsets.UTF_8);

        // A null bound means "no bound". A zero would mean a bound of zero, which for a maximum
        // forbids all borrowing -- so the migration must not name these columns at all.
        assertThat(seededValues("organization_loan_config").keySet())
                .doesNotContain("min_loan_amount", "max_loan_amount", "min_tenure_months",
                        "max_tenure_months");
        assertThat(seededValues("organization_shares_config").keySet())
                .doesNotContain("min_purchase_amount", "max_purchase_amount",
                        "min_withdrawal_amount");

        // There is no such thing as a default loan product or savings plan: seeding one would put a
        // cooperative on record as offering terms it never agreed to.
        assertThat(migration)
                .as("V9 must not seed loan products")
                .doesNotContain("INSERT INTO organization_loan_type");
        assertThat(migration)
                .as("V9 must not seed savings plans")
                .doesNotContain("INSERT INTO organization_savings_plan");

        // A migration has no actor, and actor_user_id is NOT NULL.
        assertThat(migration)
                .as("V9 must not write audit rows: a default nobody chose has no actor")
                .doesNotContain("INSERT INTO organization_config_audit");
    }

    // ================================================================================ parsing

    /**
     * The columns {@code V9} sets for one table, mapped to the literal it sets them to.
     *
     * <p>Quotes are stripped from text literals so {@code 'NONE'} compares as {@code NONE}. The
     * bookkeeping columns are dropped -- {@code organization_id} is {@code o.id} and the timestamps
     * are {@code now()}, neither of which is a default anybody decided.
     */
    private static Map<String, String> seededValues(String table) throws IOException {
        String migration = Files.readString(SEED_MIGRATION, StandardCharsets.UTF_8);

        int insertAt = migration.indexOf("INSERT INTO " + table + " (");
        assertThat(insertAt)
                .as("V9 must seed %s; if this is -1 the assertions in this class prove nothing",
                        table)
                .isNotNegative();

        int columnsOpen = migration.indexOf('(', insertAt);
        int columnsClose = migration.indexOf(')', columnsOpen);
        List<String> columns = splitList(migration.substring(columnsOpen + 1, columnsClose));

        int selectAt = migration.indexOf("SELECT", columnsClose);
        int fromAt = migration.indexOf("FROM", selectAt);
        List<String> values = splitList(
                migration.substring(selectAt + "SELECT".length(), fromAt));

        assertThat(values)
                .as("%s: the seed names %s columns and supplies %s values", table, columns.size(),
                        values.size())
                .hasSameSizeAs(columns);

        Map<String, String> seeded = new LinkedHashMap<>();
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            if (UNINTERESTING.contains(column)) {
                continue;
            }
            seeded.put(column, unquote(values.get(index)));
        }
        return seeded;
    }

    private static List<String> splitList(String raw) {
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.replaceAll("\\s+", " ").trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private static String unquote(String literal) {
        if (literal.length() >= 2 && literal.startsWith("'") && literal.endsWith("'")) {
            return literal.substring(1, literal.length() - 1);
        }
        return literal;
    }
}
