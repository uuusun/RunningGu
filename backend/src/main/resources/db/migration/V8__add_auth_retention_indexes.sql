CREATE INDEX ix_email_verification_expires_at
    ON email_verification(expires_at);

CREATE INDEX ix_email_verification_verified_at
    ON email_verification(verified_at);

CREATE INDEX ix_refresh_token_expires_at
    ON refresh_token(expires_at);
