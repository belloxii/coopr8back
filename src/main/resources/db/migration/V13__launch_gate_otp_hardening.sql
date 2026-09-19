-- OTP values are credentials. Existing outstanding values are deliberately invalidated: they
-- cannot be hashed retroactively, and preserving them would retain plaintext reset credentials.
ALTER TABLE otp ADD COLUMN failed_attempts integer NOT NULL DEFAULT 0;
UPDATE otp SET otp = NULL;

ALTER TABLE otp
    ADD CONSTRAINT ck_otp_failed_attempts CHECK (failed_attempts >= 0 AND failed_attempts <= 5);
