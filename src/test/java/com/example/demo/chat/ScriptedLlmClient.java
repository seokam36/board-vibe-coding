package com.example.demo.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.LlmUnavailableException;

// 테스트용 가짜 AI(Mockito 대신 직접 만든 "대본 읽는 배우"). chatJson(1번째 호출)과 chat(2번째 호출)이 미리 정한 답을 돌려주고,
// 받은 인자(지시문·질문·스키마·제한 시간)를 기록해 둬서 "AI가 뭘 받았나"를 나중에 확인할 수 있다.
// S17-e5: "이 호출은 N초 걸린 척"(jsonTakes/chatTakes)도 할 수 있다. 진짜로 기다리지 않고 가짜 시계(SkewClock)를 앞으로 돌린다.
public class ScriptedLlmClient implements LlmClient {

	// 호출 한 번의 기록. schema는 chatJson일 때만 채워진다. timeout은 호출 쪽이 넘긴 제한 시간(제한 없는 2인자 chat이면 null).
	public record Call(String system, String user, String schema, Duration timeout) {
	}

	// "테스트 시작 때 고정한 시각"에서 advance로만 움직이는 시계. 진짜 시간이 섞이지 않아서 시간 계산이 완전히 결정적이다
	// (전체 테스트가 느려져 지연이 생겨도 "남은 시간이 정확히 40초" 같은 값이 흔들리지 않는다).
	// 📦 java.time.Clock: "지금 몇 시"를 알려 주는 자바 시계. ChatService가 이걸 받아 쓰므로, 테스트가 이 가짜로 바꿔 끼워 시간을 건너뛴다.
	// 시각은 reset() 때마다 진짜 "지금"으로 다시 맞춘다(resetSkew). 같은 컨텍스트의 다른 시계 사용처(메시지 시각·사진 청소부·비자 날짜)는
	// 챗봇 테스트에서 쓰이지 않고, 로그인 토큰 만료는 Clock을 쓰지 않아(확인함) 멈춘 시계에 영향이 없다.
	public static class SkewClock extends Clock {
		private final ZoneId zone;
		private final AtomicReference<Instant> now;

		SkewClock(ZoneId zone, AtomicReference<Instant> now) {
			this.zone = zone;
			this.now = now;
		}

		public SkewClock() {
			this(ZoneId.of("Asia/Seoul"), new AtomicReference<>(Instant.now()));
		}

		// 시계를 d만큼 돌린다(음수면 뒤로: 시스템 시각이 뒤로 간 상황 흉내).
		public void advance(Duration d) {
			now.updateAndGet(t -> t.plus(d));
		}

		// 시각을 진짜 "지금"으로 다시 고정한다(앞 테스트가 돌려 놓은 것을 되돌림).
		public void resetSkew() {
			now.set(Instant.now());
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId z) {
			return new SkewClock(z, now);
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}

	public final List<Call> jsonCalls = new CopyOnWriteArrayList<>();
	public final List<Call> chatCalls = new CopyOnWriteArrayList<>();
	private final SkewClock clock;
	private volatile Supplier<String> jsonReply;
	private volatile Supplier<String> chatReply;
	private volatile Duration jsonTakes;
	private volatile Duration chatTakes;

	public ScriptedLlmClient(SkewClock clock) {
		this.clock = clock;
		reset();
	}

	// 테스트마다 처음 상태로: 기록을 비우고, 가짜 시계와 걸린 시간 대본도 되돌리고, 대본이 없으면 "AI 사용 불가"로 답하게 한다(대본 없이 불린 호출이 눈에 띄게)
	public void reset() {
		jsonCalls.clear();
		chatCalls.clear();
		clock.resetSkew();
		jsonTakes = Duration.ZERO;
		chatTakes = Duration.ZERO;
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

	// 양식 호출(chatJson)이 d만큼 걸린 척한다: 호출이 끝나기 전에 가짜 시계를 d만큼 앞으로 돌린다.
	public void jsonTakes(Duration d) {
		jsonTakes = d;
	}

	// 답 호출(chat)이 d만큼 걸린 척한다.
	public void chatTakes(Duration d) {
		chatTakes = d;
	}

	@Override
	public String chat(String systemPrompt, String userMessage) {
		return doChat(new Call(systemPrompt, userMessage, null, null));
	}

	@Override
	public String chat(String systemPrompt, String userMessage, Duration timeout) {
		return doChat(new Call(systemPrompt, userMessage, null, timeout));
	}

	private String doChat(Call call) {
		chatCalls.add(call);
		clock.advance(chatTakes);
		return chatReply.get();
	}

	@Override
	public String chatJson(String systemPrompt, String userMessage, String jsonSchema, Duration timeout) {
		jsonCalls.add(new Call(systemPrompt, userMessage, jsonSchema, timeout));
		clock.advance(jsonTakes);
		return jsonReply.get();
	}

	// 테스트 클래스에서 @Import로 불러 쓴다. @Primary라 진짜 LlmClient(끔/Ollama)와 진짜 Clock 대신 이 가짜들이 ChatService에 들어간다.
	// (가짜 시계 빈 이름을 진짜 "clock"과 다르게 해서 이름 충돌을 피한다.)
	@TestConfiguration
	public static class Config {
		@Bean
		@Primary
		SkewClock skewClock() {
			return new SkewClock();
		}

		@Bean
		@Primary
		ScriptedLlmClient scriptedLlmClient(SkewClock skewClock) {
			return new ScriptedLlmClient(skewClock);
		}
	}
}
