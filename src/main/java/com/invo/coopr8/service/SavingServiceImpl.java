package com.invo.coopr8.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.invo.coopr8.model.Saving;
import com.invo.coopr8.model.User;
import com.invo.coopr8.repository.SavingRepository;
import com.invo.coopr8.repository.UserRepository;
import com.invo.coopr8.utils.TxnIdGen;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SavingServiceImpl implements SavingService {

    private final SavingRepository savingRepository;
    private final UserRepository userRepository;
    private final OrganizationService organizationService;

    @Override
    @Transactional
    public Saving saveNow(User user) {
        if (user == null || user.getSavingPlan() == null) {
            throw new IllegalArgumentException("User or Monthly Plan cannot be null");
        }

        // Ensure existing balance is not null
        BigDecimal currentBalance = user.getSavingsBalance() != null ? user.getSavingsBalance() : BigDecimal.ZERO;
        BigDecimal updatedBalance = currentBalance.add(user.getSavingPlan());

        // Update user's savings balance
        user.setSavingsBalance(updatedBalance);
        userRepository.save(user);

        // Create and save new saving record, owned by the member's organization
        Saving newSaving = Saving.builder()
            .amount(user.getSavingPlan())
            .txnId(TxnIdGen.generateTransactionId())
            .balance(updatedBalance)
            .status("approved")
            .channel("PAYSTACK")
            .user(user)
            .organization(organizationService.requireForUser(user))
            .build();

        log.info("Saving recorded: User={}, Amount={}, New Balance={}", user.getId(), user.getSavingPlan(), updatedBalance);

        return savingRepository.save(newSaving);
    }
}