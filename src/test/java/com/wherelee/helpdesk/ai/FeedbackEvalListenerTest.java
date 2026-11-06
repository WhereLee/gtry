package com.wherelee.helpdesk.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.events.AiFeedbackAcceptedEvent;
import com.wherelee.helpdesk.wiki.WikiRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FeedbackEvalListenerTest {

    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final FeedbackEvalListener listener =
            new FeedbackEvalListener(wikiRepository, new ObjectMapper());

    @Test
    void addsAcceptedFeedbackAsEvalQuestion() {
        UUID tenantId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        when(wikiRepository.findFirstSectionId(pageId)).thenReturn(Optional.of(sectionId));

        listener.onAccepted(new AiFeedbackAcceptedEvent(
                tenantId, ticketId, messageId, "ADOPT", "客户问题",
                "[{\"pageId\":\"" + pageId + "\"}]"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WikiRepository.QuestionRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(wikiRepository).insertQuestions(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(row -> {
            assertThat(row.tenantId()).isEqualTo(tenantId);
            assertThat(row.sectionId()).isEqualTo(sectionId);
            assertThat(row.question()).isEqualTo("客户问题");
            assertThat(row.kind()).isEqualTo("FEEDBACK");
        });
    }

    @Test
    void skipsWhenThereAreNoCitations() {
        listener.onAccepted(new AiFeedbackAcceptedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ADOPT", "客户问题", "[]"));

        verifyNoInteractions(wikiRepository);
    }
}
