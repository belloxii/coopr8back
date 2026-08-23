package com.invo.coopr8.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.invo.coopr8.exception.SavingException;
import com.invo.coopr8.model.Saving;
import com.invo.coopr8.repository.SavingRepository;
import com.invo.coopr8.security.AuthPrincipal;
import com.invo.coopr8.security.CurrentAuth;

import lombok.AllArgsConstructor;

/**
 * A member's own savings postings.
 *
 * <p>Administrators read other members' savings through {@code /api/admin/savings/**}, which
 * applies the same organization scope. There is deliberately no member-reachable
 * {@code /user/{userId}} route here.
 */
@RestController
@RequestMapping("/api/savings")
@AllArgsConstructor
public class SavingController {

    private final SavingRepository savingRepository;

    @GetMapping("/mysavings")
    public List<Saving> mySavings() throws SavingException {
        AuthPrincipal principal = CurrentAuth.require();
        return savingRepository.findByUser_IdAndOrganizationId(
                principal.userId(), principal.organizationId());
    }

    /**
     * One savings posting by id.
     *
     * <p>Scoped to the caller's cooperative and then to the member the posting belongs to. It
     * used to be a bare {@code findById}, so any authenticated member could read any savings row
     * on the platform -- amount, date and balance -- by walking the ids.
     */
    @GetMapping("/{savingId}")
    public Saving savingById(@PathVariable Long savingId) throws SavingException {
        AuthPrincipal principal = CurrentAuth.require();

        Saving saving = savingRepository
                .findByIdAndOrganizationId(savingId, principal.organizationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Savings record not found."));

        boolean own = saving.getUser() != null
                && principal.userId().equals(saving.getUser().getId());
        if (!own && !principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Savings record not found.");
        }
        return saving;
    }

    @GetMapping("/last")
    public Optional<Saving> lastSavings() throws SavingException {
        AuthPrincipal principal = CurrentAuth.require();
        return savingRepository.findTopByUser_IdAndOrganizationIdOrderByIdDesc(
                principal.userId(), principal.organizationId());
    }
}
