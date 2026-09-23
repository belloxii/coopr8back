-- Financial state is mutated concurrently by callbacks, batch postings and administrators.
-- Optimistic versions turn a stale write into a retryable failure rather than silently losing money.
ALTER TABLE users ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE loan ADD COLUMN version bigint NOT NULL DEFAULT 0;

-- This is a tenant-owned onboarding policy. It applies to newly issued temporary passwords;
-- existing members retain their current per-user flag so an operator's in-progress reset is not undone.
ALTER TABLE organization_membership_config
    ADD COLUMN require_initial_password_change boolean NOT NULL DEFAULT false;
