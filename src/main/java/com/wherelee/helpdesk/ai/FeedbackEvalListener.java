package com.wherelee.helpdesk.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.helpdesk.events.AiFeedbackAcceptedEvent;
import com.wherelee.helpdesk.wiki.WikiRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class FeedbackEvalListener {

    private static final Logger log = LoggerFactory.getLogger(FeedbackEvalListener.class);

    private final WikiRepository wikiRepository;
    private final ObjectMapper objectMapper;

    public FeedbackEvalListener(WikiRepository wikiRepository, ObjectMapper objectMapper) {
        this.wikiRepository = wikiRepository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onAccepted(AiFeedbackAcceptedEvent event) {
        try {
            JsonNode citations = objectMapper.readTree(event.citationsJson());
            if (!citations.isArray() || citations.isEmpty()) {
                return;
            }
            String pageIdText = citations.get(0).path("pageId").asText();
            if (pageIdText.isBlank()) {
                return;
            }
            UUID pageId = UUID.fromString(pageIdText);
            UUID sectionId = wikiRepository.findFirstSectionId(pageId).orElse(null);
            if (sectionId == null) {
                return;
            }
            wikiRepository.insertQuestions(List.of(new WikiRepository.QuestionRow(
                    UUID.randomUUID(), event.tenantId(), sectionId, event.question(), "FEEDBACK")));
            log.info("helpdesk_feedback_eval_added tenant={} ticket={} page={}",
                    event.tenantId(), event.ticketId(), pageId);
        } catch (Exception ex) {
            log.warn("helpdesk_feedback_eval_skipped ticket={} error={}",
                    event.ticketId(), ex.getMessage());
        }
    }
}
