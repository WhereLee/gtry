package com.wherelee.helpdesk.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full HTTP + SSE path with the mock provider, so it needs PostgreSQL but no
 * external LLM or embedding service. Enable with HELPDESK_IT=true.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "spring.flyway.url=jdbc:postgresql://127.0.0.1:5432/helpdesk_test",
        "helpdesk.llm.provider=mock",
        "helpdesk.embedding.provider=mock",
        "helpdesk.rerank.enabled=false",
        "helpdesk.embedding.dimension=768"
})
@EnabledIfEnvironmentVariable(named = "HELPDESK_IT", matches = "true")
class AiFlowTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void suggestionStreamsAndIsPersisted() {
        String token = login();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> created = rest.exchange(
                url("/api/tickets"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "subject", "拆封的耳机能退吗",
                        "description", "上周买的耳机拆封了，还能退吗？"
                ), headers),
                Map.class
        );
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        String ticketId = String.valueOf(created.getBody().get("id"));

        HttpHeaders streamHeaders = new HttpHeaders();
        streamHeaders.setBearerAuth(token);
        ResponseEntity<String> stream = rest.exchange(
                url("/api/tickets/" + ticketId + "/ai-suggest"), HttpMethod.POST,
                new HttpEntity<>(streamHeaders), String.class
        );
        assertThat(stream.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(stream.getBody()).contains("event:done");
        assertThat(stream.getBody()).contains("messageId");

        HttpHeaders readHeaders = new HttpHeaders();
        readHeaders.setBearerAuth(token);
        ResponseEntity<Map> detail = rest.exchange(
                url("/api/tickets/" + ticketId), HttpMethod.GET,
                new HttpEntity<>(readHeaders), Map.class
        );
        assertThat(detail.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(detail.getBody().get("messages").toString()).contains("AI");
    }

    private String login() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange(
                url("/api/auth/login"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "tenantSlug", "acme",
                        "email", "agent@acme.test",
                        "password", "agent123"
                ), headers),
                Map.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return String.valueOf(response.getBody().get("token"));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }
}
