CREATE TABLE tenant_ai_config (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id) ON DELETE CASCADE,
    model VARCHAR(64) NOT NULL DEFAULT 'deepseek-flash',
    top_k INT NOT NULL DEFAULT 5,
    extra_instructions TEXT NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE tenant_ai_config ADD CONSTRAINT tenant_ai_config_top_k_check
    CHECK (top_k BETWEEN 1 AND 10);

ALTER TABLE tenant_ai_config ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_ai_config_isolation ON tenant_ai_config
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON tenant_ai_config TO helpdesk_app;

INSERT INTO tenant_ai_config (tenant_id, model, top_k, extra_instructions, enabled)
VALUES
    ('00000000-0000-0000-0000-000000000002', 'deepseek-flash', 5, '', true),
    ('00000000-0000-0000-0000-000000000003', 'deepseek-flash', 5, '', true)
ON CONFLICT (tenant_id) DO NOTHING;
