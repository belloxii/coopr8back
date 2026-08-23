package com.invo.coopr8.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EmailDetails {

    private String recipient;
    private String subject;
    private String message;
    private String attachment;

    /**
     * Display name the recipient sees as the sender, e.g. {@code ABC Cooperative}.
     *
     * <p>Set this to the sending organization's name so a member sees their own
     * cooperative in the inbox rather than the platform or another tenant. The
     * underlying mailbox address is platform-owned and unchanged. Null/blank falls back
     * to the raw address.
     */
    private String senderName;
}
