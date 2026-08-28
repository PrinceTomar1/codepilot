-- pull_requests.status was the only status column without a CHECK constraint and default,
-- unlike code_repositories.status and index_jobs.status. The application only ever writes
-- PENDING_REVIEW / REVIEWED / REVIEW_FAILED (see WebhookService, PrReviewService).
ALTER TABLE pull_requests
    ALTER COLUMN status SET DEFAULT 'PENDING_REVIEW',
    ADD CONSTRAINT chk_pull_requests_status
        CHECK (status IN ('PENDING_REVIEW', 'REVIEWED', 'REVIEW_FAILED'));
