package com.wherelee.helpdesk.wiki;

import java.util.UUID;

public record WikiSection(UUID id, UUID pageId, int seq, String headingPath, String content) {
}
