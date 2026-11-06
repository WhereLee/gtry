CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- The application connects as a non-superuser so row-level security is actually
-- enforced. Flyway runs as the database owner and creates the role here.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'helpdesk_app') THEN
        CREATE ROLE helpdesk_app LOGIN PASSWORD 'helpdesk_app';
    END IF;
END
$$;

CREATE TABLE tenant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    plan VARCHAR(32) NOT NULL DEFAULT 'free',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    monthly_token_budget BIGINT NOT NULL DEFAULT 200000,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);

CREATE TABLE channel (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    name VARCHAR(128) NOT NULL,
    type VARCHAR(32) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, key_hash)
);

CREATE TABLE ticket (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    channel_id UUID REFERENCES channel(id) ON DELETE SET NULL,
    external_ref VARCHAR(128),
    subject VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    assignee_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ticket_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    ticket_id UUID NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    author_type VARCHAR(16) NOT NULL,
    author_id UUID,
    content TEXT NOT NULL,
    citations JSONB NOT NULL DEFAULT '[]'::jsonb,
    tokens INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kb_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(512),
    content TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kb_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES kb_document(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(768) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, seq)
);

CREATE TABLE usage_event (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    tokens INT NOT NULL DEFAULT 0,
    cost_micros BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    actor_id UUID,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    target_id VARCHAR(64),
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_tenant_email ON app_user (tenant_id, email);
CREATE INDEX idx_ticket_tenant_status ON ticket (tenant_id, status, created_at DESC);
CREATE INDEX idx_ticket_assignee ON ticket (tenant_id, assignee_id);
CREATE INDEX idx_message_ticket ON ticket_message (tenant_id, ticket_id, created_at);
CREATE INDEX idx_kb_chunk_doc ON kb_chunk (tenant_id, document_id, seq);
CREATE INDEX idx_kb_chunk_embedding ON kb_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_usage_tenant_time ON usage_event (tenant_id, created_at);
CREATE INDEX idx_audit_tenant_time ON audit_log (tenant_id, created_at);

-- Tenant isolation. The application connects as helpdesk_app, which is neither
-- the table owner nor a superuser, so these policies are actually enforced.
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE channel ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket ENABLE ROW LEVEL SECURITY;
ALTER TABLE ticket_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE kb_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE kb_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY app_user_isolation ON app_user
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY channel_isolation ON channel
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY ticket_isolation ON ticket
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY ticket_message_isolation ON ticket_message
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY kb_document_isolation ON kb_document
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY kb_chunk_isolation ON kb_chunk
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY usage_event_isolation ON usage_event
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY audit_log_isolation ON audit_log
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT USAGE ON SCHEMA public TO helpdesk_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO helpdesk_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO helpdesk_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO helpdesk_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO helpdesk_app;
