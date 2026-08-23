package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BatchUserUploadRow {
    private int rowNumber;
    private UserRequest user;
}
