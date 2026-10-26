-- People who sign in (shop owners, helpers, our field team). Shops and memberships come in V3.
CREATE TABLE users (
    id                 UUID         PRIMARY KEY,
    phone              VARCHAR(10)  NOT NULL UNIQUE,      -- 10-digit Indian mobile, no +91
    name               VARCHAR(100),
    email              VARCHAR(254),
    email_verified_at  TIMESTAMPTZ,
    admin              BOOLEAN      NOT NULL DEFAULT FALSE, -- Apni Dukan field team
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

-- A verified email belongs to one person only (it is used for account recovery).
CREATE UNIQUE INDEX ux_users_verified_email ON users (lower(email)) WHERE email_verified_at IS NOT NULL;

-- Long-lived sign-in tokens, one per device. Only a SHA-256 of the token is stored, never the token itself.
CREATE TABLE refresh_tokens (
    id           UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id),
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    device       VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    -- Why it stopped working. Only a ROTATED token coming back means it was copied.
    revoke_reason VARCHAR(30)  CHECK (revoke_reason IN ('ROTATED', 'EXPIRED', 'SIGNED_OUT', 'SIGNED_OUT_EVERYWHERE', 'REUSE_DETECTED')),
    CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
