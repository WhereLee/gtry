CREATE TABLE tool_call_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    ticket_id UUID REFERENCES ticket(id) ON DELETE CASCADE,
    tool_name VARCHAR(64) NOT NULL,
    arguments JSONB NOT NULL DEFAULT '{}'::jsonb,
    result JSONB,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tool_call_ticket ON tool_call_log (tenant_id, ticket_id, created_at DESC);

ALTER TABLE tool_call_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY tool_call_log_isolation ON tool_call_log
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON tool_call_log TO helpdesk_app;
