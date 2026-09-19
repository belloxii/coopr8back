package com.invo.coopr8.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.config.PlatformJwtProvider;
import com.invo.coopr8.dto.platform.PlatformLoginRequest;
import com.invo.coopr8.dto.platform.PlatformLoginResponse;
import com.invo.coopr8.model.PlatformAdmin;
import com.invo.coopr8.repository.PlatformAdminRepository;
import com.invo.coopr8.security.PlatformAdminPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Platform super-admin authentication endpoint.
 */
@RestController
@RequestMapping("/api/platform/auth")
@RequiredArgsConstructor
public class PlatformAuthController {

    private final PlatformAdminRepository platformAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformJwtProvider platformJwtProvider;

    @PostMapping("/login")
    public PlatformLoginResponse login(@Valid @RequestBody PlatformLoginRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        PlatformAdmin admin = platformAdminRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), admin.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (!"ACTIVE".equalsIgnoreCase(admin.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is not active");
        }

        String jwt = platformJwtProvider.generateToken(admin);

        return PlatformLoginResponse.builder()
                .jwt(jwt)
                .adminId(admin.getId())
                .email(admin.getEmail())
                .role(PlatformAdminPrincipal.ROLE)
                .build();
    }
}

