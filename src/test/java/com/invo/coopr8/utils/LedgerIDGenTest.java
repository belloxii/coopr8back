package com.invo.coopr8.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for ledger-ID generation and for reading a cooperative's prefix back out of a
 * membership number.
 *
 * <p>{@link LedgerIDGen#prefixOf(String)} is load-bearing for authentication: it is the fallback
 * that lets an existing CBMC member type {@code CBMC0001} with no cooperative in the URL and
 * still reach the right tenant (D1 -- "existing CBMC ledger IDs remain unchanged"). A fallback
 * that guesses generously is a tenant-confusion bug, so most of these tests are about the inputs
 * it must refuse. It fails closed by returning {@code null}, and the caller still has to find
 * exactly one organization claiming the prefix before that means anything.
 *
 * <p>No database, no Docker, no Spring context.
 */
class LedgerIDGenTest {

    // ---------------------------------------------------------------- generation

    @Test
    void aLedgerIdIsThePrefixFollowedByAFourDigitSequence() {
        assertThat(LedgerIDGen.generate("ABC", 1)).isEqualTo("ABC0001");
        assertThat(LedgerIDGen.generate("ABC", 42)).isEqualTo("ABC0042");
        assertThat(LedgerIDGen.generate("ABC", 9999)).isEqualTo("ABC9999");
    }

    @Test
    void existingCbmcMembershipNumbersKeepTheirShape() {
        // D1: the existing cooperative keeps ledger_prefix = CBMC, and its members' numbers are
        // not rewritten. Generation for that tenant must therefore be byte-identical to what the
        // old hardcoded rule produced.
        assertThat(LedgerIDGen.generate("CBMC", 1)).isEqualTo("CBMC0001");
        assertThat(LedgerIDGen.generate("CBMC", 137)).isEqualTo("CBMC0137");
    }

    @Test
    void thePrefixIsNormalisedToUpperCaseAndTrimmed() {
        assertThat(LedgerIDGen.generate("abc", 1)).isEqualTo("ABC0001");
        assertThat(LedgerIDGen.generate("  aBc  ", 1)).isEqualTo("ABC0001");
        assertThat(LedgerIDGen.normalizePrefix(" xyz ")).isEqualTo("XYZ");
        assertThat(LedgerIDGen.normalizePrefix(null))
                .as("null in, null out -- callers decide what an absent prefix means")
                .isNull();
    }

    @Test
    void aSequencePastFourDigitsGrowsRatherThanTruncating() {
        // Documenting the behaviour rather than asserting a preference: a wider number is
        // still parseable by prefixOf, whereas truncation would silently collide with an
        // existing member's number.
        assertThat(LedgerIDGen.generate("ABC", 10_000)).isEqualTo("ABC10000");
        assertThat(LedgerIDGen.prefixOf(LedgerIDGen.generate("ABC", 10_000))).isEqualTo("ABC");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "AB1", "A1", "123", "AB-C", "AB C", "AB_C", "ÀBÇ", "AB.C"})
    void generationRefusesAPrefixThatIsNotLettersOnly(String prefix) {
        // There is deliberately no default prefix -- not a legacy society code, not the COOPR8
        // platform name -- because a fallback would mint IDs under the wrong identity.
        assertThatThrownBy(() -> LedgerIDGen.generate(prefix, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledgerPrefix");
        assertThat(LedgerIDGen.isValidPrefix(prefix)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "AB", "ABC", "abc", "aBc", "  ABC  ", "COOPERATIVESOCIETY"})
    void aLettersOnlyPrefixIsAccepted(String prefix) {
        assertThat(LedgerIDGen.isValidPrefix(prefix)).isTrue();
    }

    // ---------------------------------------------------------------- derivation

    @Test
    void thePrefixCanBeReadBackOutOfAMembershipNumber() {
        assertThat(LedgerIDGen.prefixOf("CBMC0001")).isEqualTo("CBMC");
        assertThat(LedgerIDGen.prefixOf("cbmc0001")).isEqualTo("CBMC");
        assertThat(LedgerIDGen.prefixOf("  CBMC0001  ")).isEqualTo("CBMC");
        assertThat(LedgerIDGen.prefixOf("A1")).isEqualTo("A");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "   ",           // nothing to derive from
        "CBMC",          // no sequence: not a membership number
        "0001",          // no prefix
        "CBMC-0001",     // separators are not part of the format
        "CBMC 0001",
        "CB0C0001",      // digits inside the prefix: where does the prefix end?
        "CBMC0001X",     // trailing junk
        "CBMC0001'",
        "CBMC0001 OR 1=1",
        "CBMC0001; DROP TABLE users",
        "CBMC%",
        "CB_C0001",
        "СBMC0001",      // Cyrillic С -- looks like a C, is not one
    })
    void derivationReturnsNullForAnythingThatIsNotAWellFormedMembershipNumber(String candidate) {
        // Null means "no tenant", which the caller must treat as a failed login. A partial or
        // best-effort match here would be a route into another cooperative.
        assertThat(LedgerIDGen.prefixOf(candidate))
                .as("'%s' is not a membership number, so it must yield no tenant at all", candidate)
                .isNull();
    }

    @Test
    void derivationAgreesWithGenerationForEveryValidPrefix() {
        // The property the login fallback actually depends on: whatever generate() minted for a
        // cooperative, prefixOf() reads back as that cooperative's canonical prefix. If these two
        // ever disagree, members whose IDs were issued by one of them cannot log in through the
        // other.
        for (String prefix : List.of("A", "AB", "CBMC", "xyz", "  MiXeD  ", "COOPERATIVESOCIETY")) {
            for (int sequence : new int[] {1, 9, 10, 99, 100, 999, 1000, 9999, 123456}) {
                String ledgerID = LedgerIDGen.generate(prefix, sequence);

                assertThat(LedgerIDGen.prefixOf(ledgerID))
                        .as("prefixOf(generate(\"%s\", %d)) -> %s", prefix, sequence, ledgerID)
                        .isEqualTo(LedgerIDGen.normalizePrefix(prefix));
            }
        }
    }

    @Test
    void aPrefixDerivedFromOneCooperativeIsNeverAnother() {
        // Sanity on the discriminator itself: similar prefixes stay distinct, so the caller's
        // "exactly one organization claims this prefix" lookup is being asked the right question.
        assertThat(LedgerIDGen.prefixOf("ABC0001")).isNotEqualTo(LedgerIDGen.prefixOf("ABCD0001"));
        assertThat(LedgerIDGen.prefixOf("AB0001")).isEqualTo("AB");
        assertThat(LedgerIDGen.prefixOf("ABC0001")).isEqualTo("ABC");
    }
}
