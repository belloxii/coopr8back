package com.invo.coopr8.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.invo.coopr8.service.CloudinaryService;
import com.invo.coopr8.service.OTPService;
import com.invo.coopr8.service.OrganizationService;
import com.invo.coopr8.model.OtpPurpose;
import com.invo.coopr8.service.OtpCheck;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final CloudinaryService cloudinaryService;
    private final OTPService otpService;
    private final OrganizationService organizationService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam("organization") String organization,
            @RequestParam("email") String email,
            @RequestParam("otp") String otp) throws IOException {

        var tenant = organizationService.resolveRequestedOrganization(organization);
        if (otpService.verify(tenant.getId(), email, OtpPurpose.SIGNUP, otp) != OtpCheck.VALID) {
            return ResponseEntity.status(401).body(Map.of("message", "Email verification is required."));
        }

        Map<String, String> uploadResult = cloudinaryService.uploadImage(image, tenant.getId());

        return ResponseEntity.ok(uploadResult);
    }
}
