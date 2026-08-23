package com.invo.coopr8.service;

import com.invo.coopr8.dto.EmailDetails;

public interface EmailService {

    void sendEmail(EmailDetails emailDetails);
    void sendEmailAttach(EmailDetails emailDetails);
}
