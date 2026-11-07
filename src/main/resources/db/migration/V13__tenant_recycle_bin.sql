ALTER TABLE tenant ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX idx_tenant_deleted_at ON tenant (deleted_at)
    WHERE deleted_at IS NOT NULL;
