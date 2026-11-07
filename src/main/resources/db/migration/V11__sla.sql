ALTER TABLE ticket ADD COLUMN first_response_at TIMESTAMPTZ;
ALTER TABLE ticket ADD COLUMN resolved_at TIMESTAMPTZ;
ALTER TABLE ticket ADD COLUMN sla_due_at TIMESTAMPTZ;
ALTER TABLE ticket ADD COLUMN sla_breached BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_ticket_sla ON ticket (tenant_id, status, sla_due_at);
