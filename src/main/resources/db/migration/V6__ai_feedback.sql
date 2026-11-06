CREATE TABLE ai_feedback (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES ticket_message(id) ON DELETE CASCADE,
    actor_id UUID,
    action VARCHAR(16) NOT NULL,
    original_content TEXT NOT NULL,
    edited_content TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_feedback_tenant_time ON ai_feedback (tenant_id, created_at DESC);
CREATE INDEX idx_ai_feedback_message ON ai_feedback (message_id);

ALTER TABLE ai_feedback ENABLE ROW LEVEL SECURITY;

CREATE POLICY ai_feedback_isolation ON ai_feedback
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT ON ai_feedback TO helpdesk_app;
