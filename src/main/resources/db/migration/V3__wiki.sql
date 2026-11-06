DROP TABLE IF EXISTS kb_chunk;
DROP TABLE IF EXISTS kb_document;

CREATE TABLE wiki_space (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    slug VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug)
);

CREATE TABLE wiki_page (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    space_id UUID NOT NULL REFERENCES wiki_space(id) ON DELETE CASCADE,
    slug VARCHAR(160) NOT NULL,
    title VARCHAR(255) NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC',
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED',
    owner_id UUID,
    last_reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, space_id, slug)
);

CREATE TABLE wiki_section (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    seq INT NOT NULL,
    heading_path TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (page_id, seq)
);

CREATE TABLE wiki_chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    section_id UUID NOT NULL REFERENCES wiki_section(id) ON DELETE CASCADE,
    page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    search_text TEXT NOT NULL,
    tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', search_text)) STORED,
    embedding vector(768) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (section_id)
);

CREATE TABLE wiki_link (
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    from_page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    to_page_id UUID NOT NULL REFERENCES wiki_page(id) ON DELETE CASCADE,
    PRIMARY KEY (from_page_id, to_page_id)
);

CREATE TABLE eval_question (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    section_id UUID NOT NULL REFERENCES wiki_section(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    kind VARCHAR(32) NOT NULL DEFAULT 'FACT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wiki_page_space ON wiki_page (tenant_id, space_id, updated_at DESC);
CREATE INDEX idx_wiki_section_page ON wiki_section (tenant_id, page_id, seq);
CREATE INDEX idx_wiki_chunk_section ON wiki_chunk (tenant_id, section_id);
CREATE INDEX idx_wiki_chunk_tsv ON wiki_chunk USING GIN (tsv);
CREATE INDEX idx_wiki_chunk_embedding ON wiki_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_eval_question_tenant ON eval_question (tenant_id);

ALTER TABLE wiki_space ENABLE ROW LEVEL SECURITY;
ALTER TABLE wiki_page ENABLE ROW LEVEL SECURITY;
ALTER TABLE wiki_section ENABLE ROW LEVEL SECURITY;
ALTER TABLE wiki_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE wiki_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE eval_question ENABLE ROW LEVEL SECURITY;

CREATE POLICY wiki_space_isolation ON wiki_space
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY wiki_page_isolation ON wiki_page
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY wiki_section_isolation ON wiki_section
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY wiki_chunk_isolation ON wiki_chunk
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY wiki_link_isolation ON wiki_link
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY eval_question_isolation ON eval_question
    USING (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (current_setting('app.platform_admin', true) = 'true'
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON wiki_space, wiki_page, wiki_section, wiki_chunk, wiki_link, eval_question
    TO helpdesk_app;
