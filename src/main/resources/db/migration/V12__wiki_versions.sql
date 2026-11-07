CREATE TABLE wiki_page_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    version INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    sections JSONB NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (page_id, version)
);

CREATE INDEX idx_wiki_page_version_page
    ON wiki_page_version (tenant_id, page_id, version DESC);

ALTER TABLE wiki_page_version ENABLE ROW LEVEL SECURITY;

CREATE POLICY wiki_page_version_isolation ON wiki_page_version
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON wiki_page_version TO helpdesk_app;
