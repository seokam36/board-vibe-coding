package com.example.demo.permit;

import java.util.Optional;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.example.demo.permit.service.PermitTextReader;

// 테스트용 가짜 판독기(S20-d2): "사진에서 이런 글자를 읽었다고 치자"를 테스트가 정해 준다. 진짜 판독기(Vision)는 S20-e.
// text가 null이면 못 읽음(빈 값), toThrow가 있으면 예외를 던진다. onRead는 "읽는 도중"에 끼어들 일(철회 등)을 넣는 자리다.
public class FakePermitTextReader implements PermitTextReader {

	public volatile String text;
	public volatile RuntimeException toThrow;
	public volatile Runnable onRead;
	public volatile int calls;

	public void reset() {
		text = null;
		toThrow = null;
		onRead = null;
		calls = 0;
	}

	@Override
	public Optional<String> read(byte[] image, String contentType) {
		calls++;
		if (onRead != null) {
			onRead.run();
		}
		if (toThrow != null) {
			throw toThrow;
		}
		return Optional.ofNullable(text);
	}

	// @Primary: 운영 기본 판독기(NoopPermitTextReader)보다 이 가짜가 먼저 뽑힌다
	@TestConfiguration
	public static class Config {
		@Bean
		@Primary
		FakePermitTextReader fakePermitTextReader() {
			return new FakePermitTextReader();
		}
	}
}
