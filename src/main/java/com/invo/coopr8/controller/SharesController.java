package com.invo.coopr8.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.invo.coopr8.dto.SharesResponse;
import com.invo.coopr8.exception.SharesException;
import com.invo.coopr8.model.Shares;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.SharesRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;
import com.invo.coopr8.service.SharesService;
import com.invo.coopr8.service.UserService;

import lombok.AllArgsConstructor;

/**
 * Shares held by the calling member, plus the administrative approval of a withdrawal request.
 *
 * <p>The {@code /approve} and {@code /decline} routes are restricted to {@code ROLE_ADMIN} in
 * {@code AppConfig} and checked again inside {@code SharesServiceImpl}. The former
 * {@code GET /user/{userId}} route is gone: it took any member id and returned that member's
 * whole shares history, with no ownership, role or organization check. Administrators read
 * another member's shares through {@code GET /api/admin/shares/user/{userId}}.
 */
@RestController
@RequestMapping("/api/shares")
@AllArgsConstructor
public class SharesController {

    private final UserService userService;
    private final SharesService sharesService;
    private final SharesRepository sharesRepository;

    @PostMapping("/add")
    public ResponseEntity<Shares> addShares(@RequestBody Shares sharesDetails) throws SharesException {
        User user = userService.requireCurrentUser();
        return ResponseEntity.ok(sharesService.addShares(user, sharesDetails));
    }

    @PostMapping("/withdraw")
    public SharesResponse withdrawShares(@RequestBody Shares sharesDetails) throws SharesException {
        User user = userService.requireCurrentUser();
        return sharesService.withdrawShares(user, sharesDetails);
    }

    @PutMapping("/{shareId}/approve")
    public SharesResponse approveWithdraw(@PathVariable Long shareId) throws SharesException {
        return sharesService.approveWithdraw(shareId);
    }

    @PostMapping("/{shareId}/decline")
    public SharesResponse declineWithdraw(@PathVariable Long shareId,
            @RequestBody Shares sharesDetails) throws SharesException {
        return sharesService.declineWithdraw(shareId, sharesDetails);
    }

    @GetMapping("/my")
    public ResponseEntity<List<Shares>> getMyShares() throws SharesException {
        AuthPrincipal principal = CurrentAuth.require();
        return ResponseEntity.ok(sharesRepository.findByUser_IdAndOrganizationId(
                principal.userId(), principal.organizationId()));
    }
}
