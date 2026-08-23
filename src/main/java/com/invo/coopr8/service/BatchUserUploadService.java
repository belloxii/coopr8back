package com.invo.coopr8.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.AuthResponse;
import com.invo.coopr8.dto.BatchUserUploadRequest;
import com.invo.coopr8.dto.BatchUserUploadRow;
import com.invo.coopr8.dto.BatchUserUploadRowResult;
import com.invo.coopr8.dto.BatchUserUploadResult;
import com.invo.coopr8.dto.UserRequest;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.CurrentAuth;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Spreadsheet upload of pre-vetted members by an administrator.
 *
 * <p>The rows are created through {@link UserService#createAccount(UserRequest, String)}, which
 * places each new member in the calling administrator's own cooperative -- an {@code organization}
 * value in the spreadsheet cannot redirect a row elsewhere. The duplicate pre-checks here use
 * that same organization: an email or phone number already in use in <em>another</em> cooperative
 * is not a conflict, and reporting it as one would tell this administrator something about
 * another cooperative's membership.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchUserUploadService {

    private static final int MAX_ROWS = 200;

    private final UserService userService;
    private final UserRepository userRepository;
    private final Validator validator;
    private final CloudinaryService cloudinaryService;

    public BatchUserUploadResult upload(BatchUserUploadRequest request) {
        Long organizationId = CurrentAuth.requireOrganizationId();

        List<BatchUserUploadRow> rows = request == null ? null : request.getRows();
        if (rows == null || rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The upload must contain at least one row.");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A batch may contain at most " + MAX_ROWS + " users.");
        }

        List<BatchUserUploadRowResult> results = new ArrayList<>();
        Set<String> emails = new HashSet<>();
        Set<String> phones = new HashSet<>();

        for (int index = 0; index < rows.size(); index++) {
            BatchUserUploadRow row = rows.get(index);
            int rowNumber = row != null && row.getRowNumber() > 0 ? row.getRowNumber() : index + 2;
            UserRequest user = row == null ? null : row.getUser();
            String email = user == null ? null : user.getEmail();

            if (user == null) {
                results.add(rejected(rowNumber, email, "A user record is required."));
                continue;
            }

            Set<ConstraintViolation<UserRequest>> violations = validator.validate(user);
            if (!violations.isEmpty()) {
                String message = violations.stream()
                        .map(v -> v.getPropertyPath() + " " + v.getMessage())
                        .sorted()
                        .collect(Collectors.joining("; "));
                results.add(rejected(rowNumber, email, message));
                continue;
            }

            if (!emails.add(normalize(user.getEmail()))) {
                results.add(rejected(rowNumber, email, "Duplicate email in this spreadsheet."));
                continue;
            }
            if (!phones.add(normalize(user.getPhone()))) {
                results.add(rejected(rowNumber, email, "Duplicate phone number in this spreadsheet."));
                continue;
            }

            // Skip rows whose email/phone already belongs to a member of this cooperative, with
            // a clear reason, instead of letting createAccount reject them as errors.
            if (user.getEmail() != null
                    && userRepository.existsByEmailIgnoreCaseAndOrganizationId(user.getEmail(), organizationId)) {
                results.add(skipped(rowNumber, email, "Skipped — a member with this email already exists."));
                continue;
            }
            if (user.getPhone() != null
                    && userRepository.existsByPhoneAndOrganizationId(user.getPhone(), organizationId)) {
                results.add(skipped(rowNumber, email, "Skipped — a member with this phone number already exists."));
                continue;
            }

            try {
                convertDrivePassport(user);
                // Batch-uploaded members are pre-vetted, so they go live immediately.
                AuthResponse response = userService.createAccount(user, "ACTIVE");
                boolean created = "100".equals(response.getResponseCode());
                results.add(BatchUserUploadRowResult.builder()
                        .rowNumber(rowNumber)
                        .email(email)
                        .created(created)
                        .responseCode(response.getResponseCode())
                        .message(response.getResponseMessage())
                        .ledgerId(created && response.getUser() != null ? response.getUser().getLedgerID() : null)
                        .build());
            } catch (Exception exception) {
                results.add(rejected(rowNumber, email, "Could not create this user. Please retry the row."));
            }
        }

        int createdCount = (int) results.stream().filter(BatchUserUploadRowResult::isCreated).count();
        int skippedCount = (int) results.stream().filter(BatchUserUploadRowResult::isSkipped).count();
        return BatchUserUploadResult.builder()
                .totalRows(rows.size())
                .createdCount(createdCount)
                .skippedCount(skippedCount)
                .rejectedCount(rows.size() - createdCount - skippedCount)
                .results(results)
                .build();
    }

    private BatchUserUploadRowResult rejected(int rowNumber, String email, String message) {
        return BatchUserUploadRowResult.builder()
                .rowNumber(rowNumber)
                .email(email)
                .created(false)
                .responseCode("419")
                .message(message)
                .build();
    }

    private BatchUserUploadRowResult skipped(int rowNumber, String email, String message) {
        return BatchUserUploadRowResult.builder()
                .rowNumber(rowNumber)
                .email(email)
                .created(false)
                .skipped(true)
                .responseCode("409")
                .message(message)
                .build();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Converts a Google Drive passport link to a Cloudinary URL.
     * Silently fails (leaves passport unchanged) if the download or upload fails.
     */
    private void convertDrivePassport(UserRequest user) {
        String passport = user.getPassport();
        if (passport == null || !cloudinaryService.isDriveLink(passport)) {
            return;
        }

        try {
            String cloudinaryUrl = cloudinaryService.uploadImageFromUrl(passport).get("url");
            user.setPassport(cloudinaryUrl);
        } catch (Exception e) {
            // Continue -- the member is created with the original link. Logged without the
            // member's email address, since batch logs are read by platform operators.
            log.warn("Failed to convert Google Drive passport during batch upload: {}", e.getMessage());
        }
    }
}
