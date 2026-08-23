package com.invo.coopr8.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.dto.MemberLookupResponse;
import com.invo.coopr8.dto.UserProfileUpdateRequest;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.UserService;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;

/**
 * What a member may do to their own record, and the one thing they may look up about another.
 *
 * <p>Administrative operations are not here -- they live under {@code /api/admin/**}, which is
 * gated on {@code ROLE_ADMIN}. Keeping them apart is what makes it possible to say that nothing
 * reachable from this controller can change a role, a status, a membership number or a balance.
 */
@RestController
@RequestMapping("/api/user")
@AllArgsConstructor
public class UserController {

    private final UserService userService;

    private final UserRepository userRepository;

    /**
     * A member editing their own profile.
     *
     * <p>The record updated is the caller's, from the token; the body cannot name a different
     * one. It also cannot carry {@code role}, {@code status}, {@code ledgerID}, a password hash
     * or a balance, because {@link UserProfileUpdateRequest} has no such fields -- previously
     * this endpoint bound a whole {@code User}, and setting {@code "role": "ROLE_ADMIN"} in the
     * body was all it took to become an administrator.
     */
    @PutMapping("/update")
    public User updateUser(@Valid @RequestBody UserProfileUpdateRequest request) {
        return userService.updateOwnProfile(request);
    }

    /**
     * A member's own record by id.
     *
     * <p>The id has to match the caller. It stays in the path because the frontend's profile
     * route is {@code /profile/:userId} and always passes the member's own id; any other id is
     * reported as absent.
     */
    @GetMapping("/id/{userId}")
    public User findUserById(@PathVariable Long userId) {
        CurrentAuth.requireSelf(userId);
        return userService.requireCurrentUser();
    }

    /**
     * Resolves a phone number to a fellow member, for nominating loan guarantors.
     *
     * <p>Scoped to the caller's own cooperative, so a number that belongs to a member of a
     * different one is simply not found. Answers with a name and membership number only -- see
     * {@link MemberLookupResponse}.
     */
    @GetMapping("/phone/{phone}")
    public MemberLookupResponse findUserByPhone(@PathVariable String phone) {
        AuthPrincipal principal = CurrentAuth.require();
        return userRepository.findByPhoneAndOrganizationId(phone, principal.organizationId())
                .map(MemberLookupResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No member of this cooperative has that phone number."));
    }

    // PUT /api/user/user/{userId}/activate is gone. Activation is an administrative act and now
    // exists only as PUT /api/admin/user/{userId}/activate, which the frontend already calls.
    // Leaving a second, member-reachable copy of it in place was the whole problem.
}
