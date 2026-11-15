package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.demo.llm.service.OllamaLlmClient;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S17-i: 챗봇의 "진짜" 양식(ChatService.FORM_SCHEMA)을 OllamaLlmClient로 보냈을 때, Ollama로 가는 요청의 format 칸에
// 그 양식이 그대로 실리는지 본다. 진짜 Ollama 없이 가짜 HTTP 서버(JDK 내장)가 요청 본문을 받아 적는다(CI에서 돈다).
// 왜: 양식을 바꾸다 클라이언트가 스키마를 망가뜨려 실을 수 있다. 진짜 Ollama로 보는 확인은 OllamaLiveTests(환경변수 있을 때만)가 한다.
class ChatFormSchemaRequestTests {

    private static final String OK = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":true}";

    private final ObjectMapper om = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void 진짜_FORM_SCHEMA는_요청의_format에_그대로_실리고_지시문과_질문도_그대로_간다() throws IOException {
        List<String> bodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            try {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = OK.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } finally {
                ex.close();
            }
        });
        server.start();
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "m", Duration.ofSeconds(5));

        client.chatJson(ChatService.FORM_PROMPT, "자전거 있어?", ChatService.FORM_SCHEMA, Duration.ofSeconds(5));

        assertThat(bodies).hasSize(1);
        JsonNode body = om.readTree(bodies.get(0));
        // format = FORM_SCHEMA를 JSON으로 읽은 것과 같은 구조(칸 이름·타입·설명까지)
        assertThat(body.path("format")).isEqualTo(om.readTree(ChatService.FORM_SCHEMA));
        JsonNode props = body.path("format").path("properties");
        assertThat(props.has("keyword")).isTrue();
        assertThat(props.has("maxPrice")).isTrue();
        assertThat(props.size()).isEqualTo(2);
        // 지시문(system)과 질문(user)도 그대로 실린다
        JsonNode messages = body.path("messages");
        assertThat(messages.get(0).path("role").asString()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asString()).isEqualTo(ChatService.FORM_PROMPT);
        assertThat(messages.get(1).path("role").asString()).isEqualTo("user");
        assertThat(messages.get(1).path("content").asString()).isEqualTo("자전거 있어?");
    }
}
