package com.wherelee.helpdesk.wiki;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class WikiPageDtos {

    private WikiPageDtos() {
    }

    public record SectionInput(
            @NotBlank @Size(max = 255) String heading,
            @NotBlank @Size(max = 8000) String content
    ) {
    }

    public record CreatePageRequest(
            UUID spaceId,
            @NotBlank @Size(max = 255) String title,
            String visibility,
            @NotEmpty @Valid List<SectionInput> sections
    ) {
    }

    public record UpdatePageRequest(
            @NotBlank @Size(max = 255) String title,
            String visibility,
            @NotEmpty @Valid List<SectionInput> sections
    ) {
    }

    public record ImportQuestion(
            @NotBlank @Size(max = 2000) String question,
            @NotNull UUID pageId
    ) {
    }
}
