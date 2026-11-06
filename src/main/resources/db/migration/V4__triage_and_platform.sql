ALTER TABLE ticket ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'OTHER';
ALTER TABLE ticket ADD COLUMN triage_source VARCHAR(16) NOT NULL DEFAULT 'RULE';
ALTER TABLE ticket ADD COLUMN triage_reason VARCHAR(255) NOT NULL DEFAULT '';

CREATE INDEX idx_ticket_tenant_category ON ticket (tenant_id, category, created_at DESC);

-- Platform-level audit survives tenant deletion, so it must not reference tenant(id).
CREATE TABLE platform_audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_id UUID,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id VARCHAR(64),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_audit_time ON platform_audit_log (created_at DESC);

GRANT SELECT, INSERT ON platform_audit_log TO helpdesk_app;
GRANT USAGE, SELECT ON SEQUENCE platform_audit_log_id_seq TO helpdesk_app;
