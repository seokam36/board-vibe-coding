package com.example.demo.chat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.LlmUnavailableException;

// 테스트용 가짜 AI(Mockito 대신 직접 만든 "대본 읽는 배우"). chatJson(1번째 호출)과 chat(2번째 호출)이 미리 정한 답을 돌려주고,
// 받은 인자(지시문·질문·스키마)를 기록해 둬서 "AI가 뭘 받았나"를 나중에 확인할 수 있다.
public class ScriptedLlmClient implements LlmClient {

	// 호출 한 번의 기록. schema는 chatJson일 때만 채워진다.
	public record Call(String system, String user, String schema) {
	}

	public final List<Call> jsonCalls = new CopyOnWriteArrayList<>();
	public final List<Call> chatCalls = new CopyOnWriteArrayList<>();
	private volatile Supplier<String> jsonReply;
	private volatile Supplier<String> chatReply;

	public ScriptedLlmClient() {
		reset();
	}

	// 테스트마다 처음 상태로: 기록을 비우고, 대본이 없으면 "AI 사용 불가"로 답하게 한다(대본 없이 불린 호출이 눈에 띄게)
	public void reset() {
		jsonCalls.clear();
		chatCalls.clear();
		jsonReply = () -> {
			throw new LlmUnavailableException("대본 없음(chatJson)");
		};
		chatReply = () -> {
			throw new LlmUnavailableException("대본 없음(chat)");
		};
	}

	public void jsonReturns(String text) {
		jsonReply = () -> text;
	}

	public void jsonThrows(RuntimeException e) {
		jsonReply = () -> {
			throw e;
		};
	}

	public void jsonDoes(Supplier<String> action) {
		jsonReply = action;
	}

	public void chatReturns(String text) {
		chatReply = () -> text;
	}

	public void chatThrows(RuntimeException e) {
		chatReply = () -> {
			throw e;
		};
	}

	public void chatDoes(Supplier<String> action) {
		chatReply = action;
	}

	@Override
	public String chat(String systemPrompt, String userMessage) {
		chatCalls.add(new Call(systemPrompt, userMessage, null));
		return chatReply.get();
	}

	@Override
	public String chat(String systemPrompt, String userMessage, Duration timeout) {
		return chat(systemPrompt, userMessage);
	}

	@Override
	public String chatJson(String systemPrompt, String userMessage, String jsonSchema, Duration timeout) {
		jsonCalls.add(new Call(systemPrompt, userMessage, jsonSchema));
		return jsonReply.get();
	}

	// 테스트 클래스에서 @Import로 불러 쓴다. @Primary라 진짜 LlmClient(끔/Ollama) 대신 이 가짜가 ChatService에 들어간다.
	@TestConfiguration
	public static class Config {
		@Bean
		@Primary
		ScriptedLlmClient scriptedLlmClient() {
			return new ScriptedLlmClient();
		}
	}
}
