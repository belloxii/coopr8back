-- Make legacy financial rows obey the same tenant-consistency rule as payment_transaction.
-- Each constraint is deliberately additive and fails closed if historical data is inconsistent;
-- repairing cross-cooperative financial records must be an explicit, audited data operation.

ALTER TABLE loan
    ADD CONSTRAINT uk_loan_organization_id UNIQUE (organization_id, id);

ALTER TABLE loan
    ADD CONSTRAINT fk_loan_member_same_organization
        FOREIGN KEY (organization_id, user_id) REFERENCES users (organization_id, id),
    ADD CONSTRAINT fk_loan_guarantor1_same_organization
        FOREIGN KEY (organization_id, guarantor1_id) REFERENCES users (organization_id, id),
    ADD CONSTRAINT fk_loan_guarantor2_same_organization
        FOREIGN KEY (organization_id, guarantor2_id) REFERENCES users (organization_id, id);

ALTER TABLE saving
    ADD CONSTRAINT fk_saving_member_same_organization
        FOREIGN KEY (organization_id, user_id) REFERENCES users (organization_id, id);

ALTER TABLE shares
    ADD CONSTRAINT fk_shares_member_same_organization
        FOREIGN KEY (organization_id, user_id) REFERENCES users (organization_id, id);

ALTER TABLE repay
    ADD CONSTRAINT fk_repay_member_same_organization
        FOREIGN KEY (organization_id, user_id) REFERENCES users (organization_id, id),
    ADD CONSTRAINT fk_repay_loan_same_organization
        FOREIGN KEY (organization_id, loan_id) REFERENCES loan (organization_id, id);
