package com.invo.coopr8.model;

/**
 * How a member funds their monthly savings and loan repayments.
 *
 * GOVERNMENT  - salaried TESCOM staff (identified by PSN). Savings and
 *               repayments are deducted from salary automatically, so online
 *               payment (Paystack) is disabled for them.
 * SELF_PAY    - member without salary deduction. Pays manually by bank transfer
 *               or through the Paystack payment gateway on the portal.
 */
import com.fasterxml.jackson.annotation.JsonCreator;

public enum PaymentType {
    GOVERNMENT,
    SELF_PAY;

    /**
     * Lenient parser used when binding request JSON / spreadsheet cells.
     * A blank cell (very common in batch uploads) maps to {@code null} so the
     * service layer can apply its SELF_PAY default, and common aliases coming
     * from a normalized spreadsheet are recognised.
     */
    @JsonCreator
    public static PaymentType fromValue(String value) {
        if (value == null) return null;
        String key = value.trim().toLowerCase().replaceAll("[^a-z]", "");
        if (key.isEmpty()) return null;
        switch (key) {
            case "government":
            case "gov":
            case "govt":
            case "salary":
            case "salarydeduction":
            case "psn":
            case "civilservant":
                return GOVERNMENT;
            case "selfpay":
            case "self":
            case "manual":
            case "transfer":
            case "paystack":
            case "online":
                return SELF_PAY;
            default:
                return SELF_PAY;
        }
    }
}
