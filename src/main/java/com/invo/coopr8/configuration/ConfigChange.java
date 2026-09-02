package com.invo.coopr8.configuration;

/**
 * One setting that changed: what it was, and what it became.
 *
 * <p>{@code settingKey} is the setting in its Java form -- {@code interestRate}, not
 * {@code interest_rate} -- because the trail is read next to the code and the screen that edits it,
 * neither of which uses column names.
 *
 * <h2>Keys for collections</h2>
 * The audit table has no "which row" column: it was designed for the four singleton configurations,
 * where the domain alone identifies the setting. Loan types, savings plans and exclusions are
 * collections, so their key carries the row id as a prefix:
 *
 * <pre>
 *   interestRate          a cooperative-wide setting  (LOAN_CONFIG)
 *   7.interestRate        loan type 7 (LOAN_TYPE)
 *   7.active              loan type 7 being withdrawn
 *   4.pair                exclusion 4, value "3,9"    (LOAN_TYPE_EXCLUSION)
 * </pre>
 *
 * Without the prefix a trail reading "interestRate: 5 -> 9" would not say <em>which product</em>
 * changed, which is the one thing it has to say.
 *
 * <p>Either value may be null. A null {@code oldValue} means the setting had no value before -- a
 * first-ever set, or a newly created row. A null {@code newValue} means a bound was cleared. The
 * database refuses a row where the two are equal, so a submission that changes nothing writes
 * nothing.
 */
public record ConfigChange(String settingKey, String oldValue, String newValue) {

    /**
     * The field part of the key, with any collection-row prefix removed.
     *
     * <p>Used to decide whether a change is one of the rate or price changes that require a stated
     * reason, so that {@code 7.interestRate} is treated the same as {@code interestRate}.
     */
    public String field() {
        int lastDot = settingKey.lastIndexOf('.');
        return lastDot < 0 ? settingKey : settingKey.substring(lastDot + 1);
    }
}
