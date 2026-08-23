package com.invo.coopr8.support;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.invo.coopr8.utils.LedgerIDGen;

/**
 * Seeds one complete cooperative: an organization, an administrator, a member, and one row in
 * every tenant-owned table belonging to that member.
 *
 * <p>The cross-tenant tests need two of these, and they need them to <em>collide</em> wherever
 * the platform allows a collision. So the member's email address and PSN are deliberately
 * identical in every tenant this seeds:
 *
 * <ul>
 *   <li><strong>Email.</strong> D3 made addresses unique per organization, not globally -- one
 *       person may belong to two cooperatives. Seeding distinct addresses would make the
 *       OTP and password-reset tests pass for the wrong reason: a lookup that forgot the
 *       organization would still find only one row.
 *   <li><strong>PSN.</strong> {@code ux_users_org_psn} is per-organization too, and the payroll
 *       ledger upload matches members by PSN. Two members sharing a PSN across cooperatives is
 *       the case where an unscoped match credits the wrong person's savings account.
 * </ul>
 *
 * <p>Phone numbers differ per tenant, because the tests that need a shared phone number set that
 * up themselves -- the interesting question there is which member a lookup returns, and it is
 * clearer stated at the point of use.
 *
 * <p>Rows are inserted with plain SQL rather than through the repositories on purpose. The
 * repositories are the thing under test: seeding through them would mean the fixture could only
 * create data the isolation code already agrees with, and cross-tenant data is exactly what these
 * tests need to exist.
 *
 * <p>Passwords are hashed with the application's own {@code PasswordEncoder} bean, so a token
 * obtained from {@code POST /api/auth/login} against this data is a real token minted by the real
 * login path.
 */
public final class TenantFixture {

    /** The password every seeded account is given. Not a default the application knows. */
    public static final String PASSWORD = "Fixture-Passw0rd-1";

    /** Shared across tenants on purpose -- see the class comment. */
    public static final String SHARED_MEMBER_EMAIL = "member@shared.test";

    /** Shared across tenants on purpose -- see the class comment. */
    public static final String SHARED_MEMBER_PSN = "PSN-000123";

    private TenantFixture() {
    }

    /** One seeded person, with the identifiers the API addresses them by. */
    public record Person(long id, String ledgerID, String email, String phone, String psn) {
    }

    /** One seeded cooperative and the ids of its rows. */
    public record Tenant(
            long organizationId,
            String slug,
            String ledgerPrefix,
            Person admin,
            Person member,
            long loanId,
            long repayId,
            long savingId,
            long shareId,
            long notificationId) {
    }

    /**
     * Seeds a cooperative and returns every id a test needs to address it.
     *
     * @param ledgerPrefix must be letters only and unique across the tenants seeded in one test
     *                     (D1), because the login fallback resolves a tenant from it
     * @param phoneBase    a distinct digit prefix per tenant, e.g. {@code "0801"}
     */
    public static Tenant seed(JdbcTemplate jdbc, PasswordEncoder encoder,
            String slug, String ledgerPrefix, String phoneBase) {

        long organizationId = insert(jdbc, """
                INSERT INTO organizations
                    (name, legal_name, slug, ledger_prefix, status, email, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, now(), now())
                """,
                slug + " Cooperative Society",
                slug + " Cooperative Society Limited",
                slug,
                LedgerIDGen.normalizePrefix(ledgerPrefix),
                "secretary@" + slug + ".test");

        String hashedPassword = encoder.encode(PASSWORD);

        Person admin = insertUser(jdbc, organizationId, ledgerPrefix, 900, "ROLE_ADMIN",
                hashedPassword, "admin-" + slug + "@example.test", phoneBase + "0900", null,
                "GOVERNMENT");

        // SELF_PAY, so the online repayment endpoint is reachable: a salary-deduction member is
        // turned away before the loan is ever looked up, which would make a cross-tenant probe of
        // that endpoint pass without testing anything. The PSN below still matches the payroll
        // ledger upload, which does not filter on payment type.
        Person member = insertUser(jdbc, organizationId, ledgerPrefix, 1, "ROLE_MEMBER",
                hashedPassword, SHARED_MEMBER_EMAIL, phoneBase + "0001", SHARED_MEMBER_PSN,
                "SELF_PAY");

        // The administrator stands as guarantor on the member's loan, so the guarantor-decision
        // endpoints have something to accept or decline in each tenant.
        long loanId = insert(jdbc, """
                INSERT INTO loan
                    (organization_id, user_id, guarantor1_id, amount, balance, repay_amount,
                     duration, installments_paid, guarantor1status, guarantor2status,
                     purpose, status, type, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 12, 0, 'PENDING', 'PENDING', ?, 'submitted', 'normal',
                        now())
                """,
                organizationId, member.id(), admin.id(),
                new BigDecimal("120000.00"), new BigDecimal("120000.00"), new BigDecimal("10000.00"),
                slug + " loan purpose");

        long repayId = insert(jdbc, """
                INSERT INTO repay
                    (organization_id, user_id, loan_id, amount, balance, channel, loan_type,
                     status, txn_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'manual', 'normal', 'success', ?, now())
                """,
                organizationId, member.id(), loanId,
                new BigDecimal("10000.00"), new BigDecimal("110000.00"),
                "txn-repay-" + slug);

        long savingId = insert(jdbc, """
                INSERT INTO saving
                    (organization_id, user_id, amount, balance, month, channel, status, txn_id,
                     created_at)
                VALUES (?, ?, ?, ?, now(), 'manual', 'success', ?, now())
                """,
                organizationId, member.id(),
                new BigDecimal("5000.00"), new BigDecimal("5000.00"),
                "txn-saving-" + slug);

        // 'submitted' + 'debit' is the one combination the approval path will act on, so a
        // cross-tenant approval that got through would visibly move money rather than be
        // turned away by a status check for unrelated reasons.
        long shareId = insert(jdbc, """
                INSERT INTO shares
                    (organization_id, user_id, amount, balance, account_details, channel, remark,
                     status, txn_id, type, created_at)
                VALUES (?, ?, ?, ?, ?, 'transfer', ?, 'submitted', ?, 'debit', now())
                """,
                organizationId, member.id(),
                new BigDecimal("2000.00"), new BigDecimal("0.00"),
                "0123456789 / " + slug + " Bank",
                slug + " withdrawal request",
                "txn-share-" + slug);

        long notificationId = insert(jdbc, """
                INSERT INTO notification
                    (organization_id, recipient_id, sender_id, reference_id, is_read, type,
                     message, timestamp)
                VALUES (?, ?, ?, ?, false, 'LOAN_APPROVED', ?, now())
                """,
                organizationId, member.id(), admin.id(), loanId,
                "Notification belonging to " + slug);

        return new Tenant(organizationId, slug, LedgerIDGen.normalizePrefix(ledgerPrefix),
                admin, member, loanId, repayId, savingId, shareId, notificationId);
    }

    private static Person insertUser(JdbcTemplate jdbc, long organizationId, String ledgerPrefix,
            int ledgerNumber, String role, String hashedPassword, String email, String phone,
            String psn, String paymentType) {

        String ledgerID = LedgerIDGen.generate(ledgerPrefix, ledgerNumber);

        long id = insert(jdbc, """
                INSERT INTO users
                    (organization_id, ledgerid, ledger_number, first_name, last_name, email, phone,
                     psn, password, status, role, payment_type, savings_balance, loan_balance,
                     shares_balance, saving_plan, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, now())
                """,
                organizationId, ledgerID, ledgerNumber,
                "ROLE_ADMIN".equals(role) ? "Ada" : "Bola",
                ledgerID,
                email, phone, psn, hashedPassword, role, paymentType,
                new BigDecimal("5000.00"), new BigDecimal("110000.00"), new BigDecimal("0.00"),
                new BigDecimal("5000.00"));

        return new Person(id, ledgerID, email, phone, psn);
    }

    private static long insert(JdbcTemplate jdbc, String sql, Object... arguments) {
        Long id = jdbc.queryForObject(sql + " RETURNING id", Long.class, arguments);
        if (id == null) {
            throw new IllegalStateException("Insert returned no generated id: " + sql);
        }
        return id;
    }
}
