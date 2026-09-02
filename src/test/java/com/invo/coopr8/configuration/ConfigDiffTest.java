package com.invo.coopr8.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.invo.coopr8.model.InterestMethod;

/**
 * The apply-and-record mechanism every configuration endpoint writes through.
 *
 * <p>Everything asserted here is a property the audit trail depends on: that a value written is a
 * value recorded, that a value not written is not recorded, and that money compares numerically. The
 * last one is not a nicety -- {@code numeric(6,3)} comes back from PostgreSQL as {@code 0.000} while a
 * JSON body says {@code 0}, and a scale-sensitive comparison would report an interest-rate change on
 * every save and then refuse the save for having no reason.
 *
 * <p>No database and no Spring context.
 */
class ConfigDiffTest {

    // ------------------------------------------------------------------ money

    @Test
    @DisplayName("The same amount at a different scale is not a change")
    void moneyComparesNumericallyNotByEquals() {
        AtomicReference<BigDecimal> stored = new AtomicReference<>(new BigDecimal("0.000"));
        ConfigDiff diff = new ConfigDiff();

        diff.money("interestRate", stored.get(), new BigDecimal("0"), stored::set);

        assertThat(diff.isEmpty())
                .as("0.000 and 0 are the same rate; recording a change here would demand a reason "
                        + "for a save that changed nothing")
                .isTrue();
        assertThat(stored.get()).isEqualTo(new BigDecimal("0.000"));
    }

    @Test
    @DisplayName("A real amount change is applied and recorded without scientific notation")
    void moneyChangeIsRecordedInPlainDigits() {
        AtomicReference<BigDecimal> stored = new AtomicReference<>(new BigDecimal("10000000.00"));
        ConfigDiff diff = new ConfigDiff();

        diff.money("maxLoanAmount", stored.get(), new BigDecimal("25000000.00"), stored::set);

        assertThat(stored.get()).isEqualByComparingTo("25000000.00");
        assertThat(diff.changes()).singleElement().satisfies(change -> {
            assertThat(change.settingKey()).isEqualTo("maxLoanAmount");
            // 1E+7 in an audit of a financial rule is worse than useless.
            assertThat(change.oldValue()).isEqualTo("10000000");
            assertThat(change.newValue()).isEqualTo("25000000");
        });
    }

    @Test
    @DisplayName("Clearing a bound records a null, which means \"no bound\"")
    void clearingABoundRecordsNull() {
        AtomicReference<BigDecimal> stored = new AtomicReference<>(new BigDecimal("500000.00"));
        ConfigDiff diff = new ConfigDiff();

        diff.money("maxLoanAmount", stored.get(), null, stored::set);

        assertThat(stored.get()).isNull();
        assertThat(diff.changes()).singleElement().satisfies(change -> {
            assertThat(change.oldValue()).isEqualTo("500000");
            assertThat(change.newValue()).isNull();
        });
    }

    @Test
    @DisplayName("Setting a bound for the first time records a null old value")
    void settingABoundForTheFirstTimeRecordsANullOldValue() {
        AtomicReference<BigDecimal> stored = new AtomicReference<>(null);
        ConfigDiff diff = new ConfigDiff();

        diff.money("minLoanAmount", stored.get(), new BigDecimal("5000.00"), stored::set);

        assertThat(diff.changes()).singleElement().satisfies(change -> {
            assertThat(change.oldValue()).isNull();
            assertThat(change.newValue()).isEqualTo("5000");
        });
    }

    // ------------------------------------------------------------------ other types

    @Test
    void enumChangeIsRecordedByName() {
        AtomicReference<InterestMethod> stored = new AtomicReference<>(InterestMethod.NONE);
        ConfigDiff diff = new ConfigDiff();

        diff.enumValue("interestMethod", stored.get(), InterestMethod.FLAT, stored::set);

        assertThat(stored.get()).isEqualTo(InterestMethod.FLAT);
        assertThat(diff.changes()).singleElement().satisfies(change -> {
            assertThat(change.oldValue()).isEqualTo("NONE");
            assertThat(change.newValue()).isEqualTo("FLAT");
        });
    }

    @Test
    void unchangedValuesOfEveryTypeApplyNothingAndRecordNothing() {
        List<String> applied = new ArrayList<>();
        ConfigDiff diff = new ConfigDiff();

        diff.enumValue("interestMethod", InterestMethod.FLAT, InterestMethod.FLAT,
                value -> applied.add("interestMethod"));
        diff.money("interestRate", new BigDecimal("7.50"), new BigDecimal("7.5"),
                value -> applied.add("interestRate"));
        diff.number("requiredGuarantors", 2, 2, value -> applied.add("requiredGuarantors"));
        diff.flag("approvalRequired", Boolean.TRUE, Boolean.TRUE,
                value -> applied.add("approvalRequired"));
        diff.text("name", "Asset Loan", "Asset Loan", value -> applied.add("name"));

        assertThat(diff.isEmpty()).isTrue();
        assertThat(applied)
                .as("an unchanged field must not be written: the setter is the only thing that "
                        + "touches the managed entity")
                .isEmpty();
    }

    @Test
    @DisplayName("Blank text and absent text are the same absence")
    void blankTextIsTreatedAsNull() {
        AtomicReference<String> stored = new AtomicReference<>(null);
        ConfigDiff diff = new ConfigDiff();

        // Whether the frontend sends "" or omits the field must not decide whether a change is
        // recorded.
        diff.text("description", null, "   ", stored::set);
        assertThat(diff.isEmpty()).isTrue();

        diff.text("description", "  Staff loans  ", "Staff loans", stored::set);
        assertThat(diff.isEmpty())
                .as("a description that differs only in surrounding whitespace has not changed")
                .isTrue();
    }

    @Test
    @DisplayName("Text is stored trimmed, and clearing it stores null rather than an empty string")
    void textIsStoredTrimmedAndClearedToNull() {
        AtomicReference<String> stored = new AtomicReference<>("Old");
        ConfigDiff diff = new ConfigDiff();

        diff.text("description", "Old", "  New  ", stored::set);
        assertThat(stored.get()).isEqualTo("New");

        diff.text("description", "New", "", stored::set);
        assertThat(stored.get()).isNull();
        assertThat(diff.changes()).hasSize(2);
        assertThat(diff.changes().get(1).newValue()).isNull();
    }

    @Test
    @DisplayName("An event records a row coming into existence, with nothing to set")
    void eventRecordsWithoutASetter() {
        ConfigDiff diff = new ConfigDiff(4L);

        diff.event("pair", null, "3,9");

        assertThat(diff.changes()).singleElement().satisfies(change -> {
            assertThat(change.settingKey()).isEqualTo("4.pair");
            assertThat(change.oldValue()).isNull();
            assertThat(change.newValue()).isEqualTo("3,9");
        });
    }

    @Test
    void anEventThatChangesNothingIsNotRecorded() {
        ConfigDiff diff = new ConfigDiff(4L);
        diff.event("pair", "3,9", "3,9");
        assertThat(diff.isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ keys

    @Test
    @DisplayName("A collection row's changes carry its id, so the trail says which product changed")
    void collectionKeysCarryTheRowId() {
        AtomicReference<BigDecimal> stored = new AtomicReference<>(new BigDecimal("5.000"));
        ConfigDiff diff = new ConfigDiff(7L);

        diff.money("interestRate", stored.get(), new BigDecimal("9.000"), stored::set);

        ConfigChange change = diff.changes().get(0);
        assertThat(change.settingKey()).isEqualTo("7.interestRate");
        // The reason requirement matches on the field, so a per-product rate is covered by it too.
        assertThat(change.field()).isEqualTo("interestRate");
        assertThat(ConfigAuditWriter.REASON_REQUIRED_FIELDS).contains(change.field());
    }

    @Test
    @DisplayName("A singleton configuration's keys carry no prefix")
    void singletonKeysHaveNoPrefix() {
        ConfigDiff diff = new ConfigDiff();
        diff.number("requiredGuarantors", 2, 1, value -> { });

        ConfigChange change = diff.changes().get(0);
        assertThat(change.settingKey()).isEqualTo("requiredGuarantors");
        assertThat(change.field()).isEqualTo("requiredGuarantors");
    }

    @Test
    @DisplayName("A null row id behaves like a singleton rather than writing \"null.\" into the key")
    void aNullRowIdProducesNoPrefix() {
        ConfigDiff diff = new ConfigDiff(null);
        diff.flag("active", Boolean.TRUE, Boolean.FALSE, value -> { });

        assertThat(diff.changes().get(0).settingKey()).isEqualTo("active");
    }

    @Test
    void changesAreReportedInTheOrderTheyWereApplied() {
        ConfigDiff diff = new ConfigDiff();
        diff.number("minTenureMonths", null, 3, value -> { });
        diff.number("maxTenureMonths", null, 24, value -> { });
        diff.number("requiredGuarantors", 2, 1, value -> { });

        assertThat(diff.changes())
                .extracting(ConfigChange::settingKey)
                .containsExactly("minTenureMonths", "maxTenureMonths", "requiredGuarantors");
    }

    @Test
    @DisplayName("The reported list cannot be used to add a change that was never applied")
    void reportedChangesAreImmutable() {
        ConfigDiff diff = new ConfigDiff();
        diff.number("requiredGuarantors", 2, 1, value -> { });

        List<ConfigChange> changes = diff.changes();
        assertThat(changes).isUnmodifiable();
    }
}
