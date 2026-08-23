package com.invo.coopr8.dto;

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
public class UserResponse {

    private String firstName;
    private String middleName;
    private String lastName;
    private String gender;
    private String email;
    private String passport;
    private String address;
    private String state;
    private String psn;
    private String occupation;
    private String ledgerID;
    private String phone;
    private String phone2;
    private String station;
    private String status;
    private String marital;
    private String lga;

}
