package com.invo.coopr8.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Email;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The fields a member may change about their own profile.
 *
 * <p><strong>This type exists to be short.</strong> {@code PUT /api/user/update} used to
 * bind a whole {@code User} entity, so the request body decided the member's
 * {@code role}, {@code status}, {@code ledgerID}, balances and password hash: a member
 * could promote themselves to {@code ROLE_ADMIN}, or set their own savings balance, by
 * editing one JSON field. Nothing security- or money-bearing appears below, so no amount
 * of creativity in the request body can reach those columns.
 *
 * <p>There is also no {@code id}: the record updated is always the authenticated caller's,
 * taken from the JWT. Administrator-controlled fields live in
 * {@link AdminUserUpdateRequest}.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserProfileUpdateRequest {

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

    // ----------------------------------------------------------------- background
    private String occupation;
    private String passport;

    // ---------------------------------------------------------------- next of kin
    private String nextOfKin;
    private String nextOfKinRelationship;
    private String nextOfKinAddress;
    private String nextOfKinPhone;

    /**
     * The member's chosen monthly savings contribution.
     *
     * <p>A savings <em>plan</em> is an intention to pay, not a balance -- raising it costs
     * the member money rather than granting them any. Actual balances
     * ({@code savingsBalance}, {@code loanBalance}, {@code sharesBalance},
     * {@code totalPurchase}, {@code profit}) are derived from posted transactions and are
     * deliberately absent from this request.
     */
    private BigDecimal savingPlan;

    private BigDecimal specialSavingPlan;

    private BigDecimal sharePlan;
}
