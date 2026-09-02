package com.invo.coopr8.configuration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Applies an administrator's submission field by field, and records what actually changed.
 *
 * <h2>Why apply and record in the same call</h2>
 * The audit has to say what a setting <em>used to be</em>, which is knowable only before the new
 * value is written. Two separate passes -- "diff, then apply" -- work until someone adds a field to
 * one pass and forgets the other, and then the trail is quietly wrong about a financial rule.
 * Here a field cannot be written except through the call that records it:
 *
 * <pre>
 *   diff.money("interestRate", config.getInterestRate(), request.interestRate(),
 *              config::setInterestRate);
 * </pre>
 *
 * <p>A field whose submitted value equals the stored one is not written and not recorded. That is
 * what keeps "the administrator pressed Save without changing anything" out of the trail, and it is
 * also required: {@code ck_organization_config_audit_change} refuses a row whose old and new values
 * are equal.
 *
 * <h2>Money is compared numerically, not by equals</h2>
 * {@code BigDecimal.equals} is scale-sensitive, so {@code 0.000} and {@code 0} are unequal to it.
 * PostgreSQL hands back {@code numeric(6,3)} as {@code 0.000} while a JSON body says {@code 0}, so
 * an {@code equals} comparison would record an interest-rate change on every save and require a
 * reason for it. {@link #money} uses {@code compareTo}.
 *
 * <p>Not thread-safe, and not meant to be: one instance serves one request.
 */
public final class ConfigDiff {

    /** Prepended to every key, with a dot. Empty for the singleton configurations. */
    private final String keyPrefix;

    private final List<ConfigChange> changes = new ArrayList<>();

    /** For a singleton configuration, whose domain already identifies the row. */
    public ConfigDiff() {
        this.keyPrefix = "";
    }

    /**
     * For one member of a collection.
     *
     * @param rowId the loan type, savings plan or exclusion the keys refer to. See
     *              {@link ConfigChange} for why the id belongs in the key.
     */
    public ConfigDiff(Long rowId) {
        this.keyPrefix = rowId == null ? "" : rowId + ".";
    }

    // ------------------------------------------------------------------ typed fields

    /** An enumerated setting, recorded by its name. */
    public <T extends Enum<T>> void enumValue(String field, T current, T next, Consumer<T> apply) {
        if (Objects.equals(current, next)) {
            return;
        }
        record(field, current == null ? null : current.name(), next == null ? null : next.name());
        apply.accept(next);
    }

    /** A money or rate column. Compared numerically -- see the class comment. */
    public void money(String field, BigDecimal current, BigDecimal next,
            Consumer<BigDecimal> apply) {
        if (sameAmount(current, next)) {
            return;
        }
        record(field, renderAmount(current), renderAmount(next));
        apply.accept(next);
    }

    /** A whole-number setting: a tenure in months, a guarantor count, a loan cap. */
    public void number(String field, Integer current, Integer next, Consumer<Integer> apply) {
        if (Objects.equals(current, next)) {
            return;
        }
        record(field, render(current), render(next));
        apply.accept(next);
    }

    /** A yes/no setting. */
    public void flag(String field, Boolean current, Boolean next, Consumer<Boolean> apply) {
        if (Objects.equals(current, next)) {
            return;
        }
        record(field, render(current), render(next));
        apply.accept(next);
    }

    /**
     * A free-text setting: a product name, a description, a default member status.
     *
     * <p>Blank and null are treated as the same absence, so clearing a description does not depend
     * on whether the frontend sent {@code ""} or omitted the field.
     */
    public void text(String field, String current, String next, Consumer<String> apply) {
        String currentValue = trimToNull(current);
        String nextValue = trimToNull(next);
        if (Objects.equals(currentValue, nextValue)) {
            return;
        }
        record(field, currentValue, nextValue);
        apply.accept(nextValue);
    }

    /**
     * A change that has no stored field to set -- a row created or removed in its entirety.
     *
     * <p>Used for exclusions, which have no mutable columns at all: the only things that can happen
     * to one are coming into existence and ceasing to.
     */
    public void event(String field, String oldValue, String newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }
        record(field, oldValue, newValue);
    }

    // ----------------------------------------------------------------------- results

    public List<ConfigChange> changes() {
        return List.copyOf(changes);
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    // --------------------------------------------------------------------- internals

    private void record(String field, String oldValue, String newValue) {
        changes.add(new ConfigChange(keyPrefix + field, oldValue, newValue));
    }

    private static boolean sameAmount(BigDecimal current, BigDecimal next) {
        if (current == null || next == null) {
            return current == next;
        }
        return current.compareTo(next) == 0;
    }

    /**
     * A money value as the trail should show it.
     *
     * <p>{@code stripTrailingZeros} so that the same amount read back from two columns of different
     * scale renders identically, and {@code toPlainString} so a large figure is not recorded in
     * scientific notation -- {@code 1E+7} in an audit of a financial rule is worse than useless.
     */
    private static String renderAmount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String render(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
