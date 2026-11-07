CREATE TABLE ticket_attachment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    message_id UUID REFERENCES ticket_message(id) ON DELETE CASCADE,
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE ticket_attachment ADD CONSTRAINT ticket_attachment_visibility_check
    CHECK (visibility IN ('PUBLIC', 'INTERNAL'));

CREATE INDEX idx_attachment_ticket ON ticket_attachment (tenant_id, ticket_id, created_at);

ALTER TABLE ticket_attachment ENABLE ROW LEVEL SECURITY;

CREATE POLICY ticket_attachment_isolation ON ticket_attachment
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, DELETE ON ticket_attachment TO helpdesk_app;
