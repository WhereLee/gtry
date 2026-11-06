package com.wherelee.helpdesk.wiki;

import java.util.UUID;

public record WikiSpace(UUID id, UUID tenantId, String slug, String name, String description) {
}
