package com.invo.coopr8.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.dto.AuthResponse;
import com.invo.coopr8.dto.CoopResponse;
import com.invo.coopr8.dto.ForgotPasswordRequest;
import com.invo.coopr8.dto.LoginDto;
import com.invo.coopr8.dto.PasswordDto;
import com.invo.coopr8.dto.ResetPasswordRequest;
import com.invo.coopr8.dto.UserRequest;
import com.invo.coopr8.model.User;
import com.invo.coopr8.service.UserService;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;

/**
 * Signing up, signing in, and recovering an account.
 *
 * <p>Only {@code /signup}, {@code /login} and {@code /forgot-password/**} are reachable without
 * a token (see {@code AppConfig}); each resolves its own organization from the slug the caller
 * came through. Everything else here requires a verified token.
 *
 * <p><strong>No method takes the {@code Authorization} header any more.</strong> The token is
 * verified once by {@code JwtTokenValidator} and the caller is read from the security context.
 * Passing the raw header into the service layer meant parsing the same token a second time,
 * with a weaker check, to answer a question the filter had already answered.
 */
@RestController
@RequestMapping("/api/auth")
@AllArgsConstructor
public class AuthController {

    private final UserService userService;

    /**
     * Self-service signup into one cooperative.
     *
     * <p>The cooperative comes from {@code userRequest.organization} (the {@code /o/{slug}/signup}
     * URL) and the new member lands as PENDING, awaiting that cooperative's own administrator.
     */
    @PostMapping("/signup")
    public AuthResponse createAccount(@Valid @RequestBody UserRequest userRequest) {
        return userService.createAccount(userRequest);
    }

    /** {@code organization + membership number + password}. */
    @PostMapping("/login")
    public AuthResponse login(@RequestBody LoginDto loginDto) {
        return userService.login(loginDto);
    }

    @GetMapping("/profile")
    public AuthResponse getUserProfile() {
        return userService.currentUserProfile();
    }

    @PutMapping("/changepass")
    public CoopResponse changePass(@Valid @RequestBody PasswordDto passwordDto) {
        User user = userService.requireCurrentUser();
        return userService.changePassword(user, passwordDto);
    }

    /**
     * Mandatory first-login change: no OTP, because the token plus the current password already
     * establish who is asking.
     */
    @PutMapping("/change-default-pass")
    public CoopResponse changeDefaultPass(@RequestBody PasswordDto passwordDto) {
        User user = userService.requireCurrentUser();
        return userService.changePasswordSimple(user, passwordDto.getOldPassword(),
                passwordDto.getNewPassword());
    }

    // ------------------------------------------------------------- forgotten password

    /**
     * Step one: email a one-time code to the address on the member's record.
     *
     * <p>Answers identically whether or not the account exists, so it cannot be used to test
     * which membership numbers or email addresses belong to a cooperative.
     */
    @PostMapping("/forgot-password/request")
    public CoopResponse requestPasswordReset(@RequestBody ForgotPasswordRequest request) {
        return userService.requestPasswordReset(request);
    }

    /** Step two: redeem the code, inside the tenant that issued it, and set the new password. */
    @PostMapping("/forgot-password/reset")
    public CoopResponse resetPassword(@RequestBody ResetPasswordRequest request) {
        return userService.resetPassword(request);
    }
}
