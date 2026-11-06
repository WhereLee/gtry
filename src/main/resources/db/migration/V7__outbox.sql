CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

ALTER TABLE outbox_event ADD CONSTRAINT outbox_event_status_check
    CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED'));

CREATE INDEX idx_outbox_pending ON outbox_event (status, next_attempt_at, created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_event (tenant_id, aggregate_type, aggregate_id);

ALTER TABLE outbox_event ENABLE ROW LEVEL SECURITY;

CREATE POLICY outbox_event_isolation ON outbox_event
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON outbox_event TO helpdesk_app;
