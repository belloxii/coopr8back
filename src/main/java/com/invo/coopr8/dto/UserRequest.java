package com.invo.coopr8.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.invo.coopr8.model.PaymentType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRequest {

    /**
     * Slug of the cooperative being joined, e.g. {@code "citadel"} from
     * {@code /o/citadel/signup}.
     *
     * <p>Read only for anonymous self-service signup, and only to <em>select</em> which
     * tenant the new PENDING record is filed under -- it grants nothing, since an
     * administrator of that same tenant still has to approve the account. When an
     * authenticated administrator creates a member, this field is ignored entirely and the
     * organization comes from the caller's JWT, so an admin cannot plant a member in
     * another cooperative by editing the request body.
     */
    private String organization;

    @NotBlank(message = "firstName is required")
    private String firstName;
    private String middleName;
    @NotBlank(message = "lastName is required")
    private String lastName;

    @NotBlank(message = "gender is required")
    private String gender;
    private String marital;

    @NotBlank(message = "email is required")
    @Email(message = "email must be valid")
    private String email;
    @NotBlank(message = "phone is required")
    private String phone;
    @NotBlank(message = "address is required")
    private String address;

    private String psn;
    private String occupation;
    private String verNo;
    private String station;
    private String homeTown;
    private String lga;
    private String state;

    // GOVERNMENT (salary deduction) or SELF_PAY. Defaults to SELF_PAY when omitted.
    private PaymentType paymentType;

    private String passport;

    @NotNull(message = "savingPlan is required")
    @Positive(message = "savingPlan must be greater than zero")
    private BigDecimal savingPlan;
    private BigDecimal specialSavingPlan;
    private BigDecimal sharePlan;

    private String nextOfKin;
    private String nextOfKinRelationship;
    private String nextOfKinAddress;
    private String nextOfKinPhone;

    private String password;
}
