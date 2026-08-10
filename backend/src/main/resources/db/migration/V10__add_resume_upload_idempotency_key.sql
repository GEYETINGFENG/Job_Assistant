ALTER TABLE resume_upload_session
    ADD COLUMN idempotency_key VARCHAR(128);

COMMENT ON COLUMN resume_upload_session.idempotency_key
IS 'Client idempotency key for creating a new resume version';

CREATE UNIQUE INDEX uk_resume_upload_session_idempotency
    ON resume_upload_session(user_id, resume_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL
  AND upload_type = 'NEW_VERSION';