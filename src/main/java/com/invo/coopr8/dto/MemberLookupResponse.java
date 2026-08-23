package com.invo.coopr8.dto;

import com.invo.coopr8.model.User;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The little that one member may learn about another.
 *
 * <p>Guarantor nomination works by phone number: an applicant types a number and the form shows
 * whose it is, so they can confirm they picked the right colleague. That needs a name and
 * nothing else.
 *
 * <p>The endpoint used to answer with the whole {@code User} entity, which meant any member
 * could type any phone number and read that person's home address, PSN, next of kin, and
 * savings, loan and shares balances. Those fields are absent here, so the lookup can serve its
 * purpose without being a directory of everyone's finances.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MemberLookupResponse {

    /** Needed so a nomination can be recorded against a specific member. */
    private Long id;

    private String firstName;
    private String middleName;
    private String lastName;

    /**
     * Membership number, so the applicant can double-check the person they nominated.
     *
     * <p>Safe to show within one cooperative: members read each other's numbers off shared
     * ledgers and passbooks already, and a number alone is not a credential -- signing in also
     * needs the password.
     */
    private String ledgerID;

    /** {@code ACTIVE}, {@code PENDING}, ... A suspended member is a poor choice of guarantor. */
    private String status;

    public static MemberLookupResponse from(User user) {
        return MemberLookupResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .middleName(user.getMiddleName())
                .lastName(user.getLastName())
                .ledgerID(user.getLedgerID())
                .status(user.getStatus())
                .build();
    }
}
