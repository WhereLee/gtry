ALTER TABLE ticket ADD COLUMN customer_name VARCHAR(128);
ALTER TABLE ticket ADD COLUMN customer_email VARCHAR(255);
ALTER TABLE ticket ADD COLUMN customer_token_hash CHAR(64);
ALTER TABLE ticket ADD COLUMN customer_token_expires_at TIMESTAMPTZ;
ALTER TABLE ticket ADD COLUMN csat_rating INT;
ALTER TABLE ticket ADD COLUMN csat_comment VARCHAR(1000);
ALTER TABLE ticket ADD COLUMN csat_at TIMESTAMPTZ;

ALTER TABLE ticket ADD CONSTRAINT ticket_csat_rating_check
    CHECK (csat_rating IS NULL OR csat_rating BETWEEN 1 AND 5);

CREATE UNIQUE INDEX idx_ticket_customer_token
    ON ticket (customer_token_hash)
    WHERE customer_token_hash IS NOT NULL;

ALTER TABLE ticket_message ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';
ALTER TABLE ticket_message ADD CONSTRAINT ticket_message_visibility_check
    CHECK (visibility IN ('PUBLIC', 'INTERNAL'));

INSERT INTO channel (id, tenant_id, name, type, key_hash, active) VALUES
    ('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000002',
     'Acme Web Demo', 'WEB', '8676d15d94dabdd1283e6c407e2bb08a7b5c13b4e3667d4795c17172e080a3d4', true),
    ('00000000-0000-0000-0000-000000000302', '00000000-0000-0000-0000-000000000003',
     'Cloudwise Web Demo', 'WEB', '37e3d0cbeb7f9a695bedcd93813020ae3d60de8d42cc24e06f5481b3fd7ed1b3', true)
ON CONFLICT (tenant_id, key_hash) DO NOTHING;

-- Public requests have no JWT, so channel/token resolution must bypass RLS in a
-- narrowly-scoped SECURITY DEFINER function. The function returns only the ids
-- needed to establish TenantContext; the actual ticket query still runs under RLS.
CREATE OR REPLACE FUNCTION resolve_channel(p_key_hash CHAR(64))
RETURNS TABLE(channel_id UUID, tenant_id UUID)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id, tenant_id FROM channel WHERE key_hash = p_key_hash AND active = true;
$$;

CREATE OR REPLACE FUNCTION resolve_ticket_token(p_hash CHAR(64))
RETURNS TABLE(ticket_id UUID, tenant_id UUID, expires_at TIMESTAMPTZ)
LANGUAGE sql
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT id, tenant_id, customer_token_expires_at
    FROM ticket
    WHERE customer_token_hash = p_hash
      AND customer_token_expires_at > now();
$$;

GRANT EXECUTE ON FUNCTION resolve_channel(CHAR(64)) TO helpdesk_app;
GRANT EXECUTE ON FUNCTION resolve_ticket_token(CHAR(64)) TO helpdesk_app;
