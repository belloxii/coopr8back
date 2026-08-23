package com.invo.coopr8.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

import com.invo.coopr8.model.PaymentType;
import com.invo.coopr8.model.Role;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The fields an administrator may change about a member of <em>their own</em> cooperative.
 *
 * <p>Counterpart to {@link UserProfileUpdateRequest}. The split is the point: everything
 * here is privileged, so it is reachable only through {@code /api/admin/**} and only for a
 * {@link #userId} that resolves inside the calling administrator's organization. There is
 * no {@code organizationId} field -- the tenant comes from the caller's JWT, so an
 * administrator cannot edit a member of another cooperative by supplying a foreign id.
 *
 * <p>Deliberately absent:
 * <ul>
 *   <li>{@code ledgerID} -- a membership number is printed on ledgers and passbooks and is
 *       the login identifier; it is issued once and never rewritten.</li>
 *   <li>{@code password} -- an administrator does not set member passwords. Members use the
 *       reset flow, which requires access to the member's own mailbox.</li>
 *   <li>balances ({@code savingsBalance}, {@code loanBalance}, {@code sharesBalance},
 *       {@code totalPurchase}, {@code profit}) -- money moves only through posted
 *       transactions, so an audit trail always exists. Corrections go through
 *       {@code /api/admin/savings/manual} and {@code /api/admin/repays/manual}.</li>
 * </ul>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AdminUserUpdateRequest {

    /** Member to update. Resolved within the administrator's organization, or 404. */
    @NotNull(message = "userId is required")
    private Long userId;

    // ------------------------------------------------------------------- personal
    private String firstName;
    private String middleName;
    private String lastName;
    private String gender;
    private String marital;

    // -------------------------------------------------------------------- contact
    @Email(message = "email must be valid")
    private String email;
    private String phone;
    private String address;
    private String homeTown;
    private String state;
    private String lga;
    private String station;

    // -------------------------------------------------------------- identification
    private String psn;
    private String occupation;
    private String verNo;
    private String passport;

    // ---------------------------------------------------------------- next of kin
    private String nextOfKin;
    private String nextOfKinRelationship;
    private String nextOfKinAddress;
    private String nextOfKinPhone;

    // ------------------------------------------------------------------ privileged
    /** GOVERNMENT (salary deduction) or SELF_PAY. Decides how the member is credited. */
    private PaymentType paymentType;

    /** {@code ROLE_MEMBER} or {@code ROLE_ADMIN}, within this cooperative only. */
    private Role role;

    /** {@code NEW}, {@code PENDING}, {@code ACTIVE} or {@code SUSPENDED}. */
    private String status;

    // ----------------------------------------------------------------------- plans
    private BigDecimal savingPlan;
    private BigDecimal specialSavingPlan;
    private BigDecimal sharePlan;
}
