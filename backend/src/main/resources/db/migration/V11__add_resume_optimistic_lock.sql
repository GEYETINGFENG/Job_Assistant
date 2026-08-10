ALTER TABLE resume
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN resume.lock_version
IS 'JPA optimistic lock version used to detect concurrent updates';