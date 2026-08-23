package com.invo.coopr8.support;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import com.invo.coopr8.dto.EmailDetails;
import com.invo.coopr8.service.EmailService;
import com.invo.coopr8.tenant.TenantContext;

/**
 * Test double for {@link EmailService} that records what would have been sent instead of
 * sending it.
 *
 * <p>Replacing the interface (rather than {@code JavaMailSender}) also removes the
 * {@code @Async} hop that {@code EmailServiceImpl.sendEmail} runs on, so assertions are
 * deterministic: by the time the call under test returns, the message is already recorded.
 *
 * <p>Each record keeps the tenant context and thread observed at call time, which is what
 * makes the cross-tenant email assertions possible: a tenant's OTP mail must go only to
 * that tenant's member, and must never carry another organization's display name as the
 * sender.
 */
public class RecordingEmailService implements EmailService {

    /**
     * One captured message.
     *
     * @param recipient      the {@code To} address
     * @param subject        the subject line
     * @param message        the body
     * @param senderName     display name the recipient would see -- the sending
     *                       organization's name, and the field a cross-tenant branding
     *                       leak would show up in
     * @param attachment     attachment path, if any
     * @param organizationId tenant context in force when the send was requested
     * @param threadName     thread the send was requested on
     */
    public record RecordedEmail(
            String recipient,
            String subject,
            String message,
            String senderName,
            String attachment,
            Long organizationId,
            String threadName) {

        public boolean mentions(String text) {
            if (text == null || text.isBlank()) {
                return false;
            }
            String needle = text.toLowerCase(Locale.ROOT);
            return contains(subject, needle) || contains(message, needle) || contains(senderName, needle);
        }

        private static boolean contains(String haystack, String lowercaseNeedle) {
            return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
        }
    }

    private final List<RecordedEmail> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendEmail(EmailDetails emailDetails) {
        record(emailDetails);
    }

    @Override
    public void sendEmailAttach(EmailDetails emailDetails) {
        record(emailDetails);
    }

    private void record(EmailDetails emailDetails) {
        sent.add(new RecordedEmail(
                emailDetails.getRecipient(),
                emailDetails.getSubject(),
                emailDetails.getMessage(),
                emailDetails.getSenderName(),
                emailDetails.getAttachment(),
                TenantContext.getOrganizationId(),
                Thread.currentThread().getName()));
    }

    public List<RecordedEmail> sent() {
        return Collections.unmodifiableList(sent);
    }

    public List<RecordedEmail> sentTo(String recipient) {
        return sent.stream()
                .filter(email -> recipient != null && recipient.equalsIgnoreCase(email.recipient()))
                .toList();
    }

    public RecordedEmail lastSent() {
        if (sent.isEmpty()) {
            throw new AssertionError("Expected an email to have been sent, but none was.");
        }
        return sent.get(sent.size() - 1);
    }

    public void clear() {
        sent.clear();
    }
}
