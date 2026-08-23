package com.invo.coopr8.service;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.invo.coopr8.dto.EmailDetails;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class EmailServiceImpl implements EmailService{

    @Autowired
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String senderEmail;

    // Runs on a background thread so account creation (and other flows) return
    // immediately instead of blocking on the SMTP round-trip. A mail failure is
    // logged, never thrown: it must NOT roll back the caller's transaction or
    // turn a successful signup into an HTTP 500.
    @Async
    @Override
    public void sendEmail(EmailDetails emailDetails) {
        try {
            SimpleMailMessage mailMessage = new SimpleMailMessage();
            mailMessage.setFrom(resolveFrom(emailDetails));
            mailMessage.setTo(emailDetails.getRecipient());
            mailMessage.setText(emailDetails.getMessage());
            mailMessage.setSubject(emailDetails.getSubject());

            javaMailSender.send(mailMessage);

        } catch (MailException e) {
            log.error("Failed to send email to {}: {}", emailDetails.getRecipient(), e.getMessage());
        }
    }

    @Override
    public void sendEmailAttach(EmailDetails emailDetails) {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper mimeMessageHelper;
        try {
            mimeMessageHelper = new MimeMessageHelper(mimeMessage, true);
            mimeMessageHelper.setFrom(resolveFrom(emailDetails));
            mimeMessageHelper.setTo(emailDetails.getRecipient());
            mimeMessageHelper.setText(emailDetails.getMessage());
            mimeMessageHelper.setSubject(emailDetails.getSubject());

            FileSystemResource file = new FileSystemResource(new File(emailDetails.getAttachment()));
            mimeMessageHelper.addAttachment(file.getFilename(), file);
            javaMailSender.send(mimeMessage);

            log.info(file.getFilename() + "has been sent to " + emailDetails.getRecipient());
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * The {@code From} header: the platform mailbox, labelled with the sending
     * organization's name when the caller supplied one.
     *
     * <p>So a member of "ABC Cooperative" sees ABC in their inbox, not the platform and
     * never another tenant. Only the display name varies -- the address stays the single
     * configured, authenticated mailbox.
     */
    private String resolveFrom(EmailDetails emailDetails) {
        String senderName = emailDetails.getSenderName();
        if (senderName == null || senderName.isBlank()) {
            return senderEmail;
        }
        try {
            return new InternetAddress(senderEmail, senderName.trim(), StandardCharsets.UTF_8.name())
                    .toString();
        } catch (UnsupportedEncodingException e) {
            log.warn("Could not encode sender display name '{}'; sending as plain address.",
                    senderName);
            return senderEmail;
        }
    }
}
