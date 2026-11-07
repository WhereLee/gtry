#!/usr/bin/env bash
set -euo pipefail

cd /opt/helpdesk
BACKUP=${1:-backups/helpdesk-20261107-155225.dump}

sudo docker compose exec -T postgres dropdb -U postgres --if-exists helpdesk_restore_check
sudo docker compose exec -T postgres createdb -U postgres helpdesk_restore_check
sudo docker compose exec -T postgres pg_restore -U postgres -d helpdesk_restore_check \
  --no-owner --no-privileges < "$BACKUP"

sudo docker compose exec -T postgres psql -U postgres -d helpdesk_restore_check -tAc \
  "select (select count(*) from tenant) as tenants, (select count(*) from ticket) as tickets, (select count(*) from wiki_page) as pages, (select max(installed_rank) from flyway_schema_history) as flyway_rank, (select max(version::int) from flyway_schema_history) as flyway_version"

sudo docker compose exec -T postgres dropdb -U postgres helpdesk_restore_check
