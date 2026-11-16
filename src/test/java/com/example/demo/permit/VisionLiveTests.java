package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.example.demo.permit.service.VisionPermitTextReader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 진짜 구글 Vision에 작은 그림 한 장을 보내 보는 시험 (S20-e2).
// VISION_LIVE_TEST=true 이고 환경변수 VISION_API_KEY가 있을 때만 돈다(평소·CI에서는 건너뜀 = 요금·무료 한도를 쓰지 않는다).
// 그림은 Java로 영어 글자 몇 개를 그린 것이라 개인정보가 없다. 키는 환경변수에서만 읽고 출력하지 않는다.
@EnabledIfEnvironmentVariable(named = "VISION_LIVE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "VISION_API_KEY", matches = ".+")
class VisionLiveTests {

	private static final String BASE_URL = "https://vision.googleapis.com";

	// 흰 바탕에 큰 검은 글자로 "ADMISSION 2013" 같은 영어·숫자를 그린 PNG
	private static byte[] 글자그림() throws Exception {
		BufferedImage img = new BufferedImage(640, 160, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, 640, 160);
		g.setColor(Color.BLACK);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
		g.drawString("ADMISSION 2013", 20, 100);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	// 키에 공백·줄바꿈이 있으면 JDK가 "invalid header value: <키>" 예외를 던져 키 전체가 실패 메시지·보고서에 남는다.
	// 그래서 쓰기 전에 먼저 검사하고, 값 없이 이 메시지로만 실패시킨다.
	private static String 키() {
		String key = System.getenv("VISION_API_KEY");
		if (key == null || key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
			throw new IllegalStateException("VISION_API_KEY에 공백·줄바꿈이 있음(값은 출력 안 함)");
		}
		return key;
	}

	@Test
	void 진짜_Vision에_그림을_보내면_글자가_돌아온다() throws Exception {
		VisionPermitTextReader reader = new VisionPermitTextReader(BASE_URL, 키(),
				Duration.ofSeconds(20), 2, 7L * 1024 * 1024);
		try {
			Optional<String> text = reader.read(글자그림(), "image/png");

			assertThat(text).isPresent();
			// 줄바꿈·띄어쓰기는 구글 마음이라 글자 조각만 본다(출력은 그림 속 영어뿐이라 개인정보 아님)
			System.out.println("LIVE 읽은 글자: " + text.get().replace("\n", "\\n"));
			assertThat(text.get().toUpperCase()).contains("ADMISSION").contains("2013");
		} finally {
			reader.close();
		}
	}

	// X-Goog-FieldMask가 Vision v1에서 먹는지(backlog "나중(S20-e1 리뷰) ③"): 같은 요청을 직접 보내 응답 모양을 본다.
	// 먹으면 응답에 글자(fullTextAnnotation.text)만 있고 큰 덩어리(pages·textAnnotations)는 없다.
	@Test
	void FieldMask_헤더가_먹으면_응답에_글자만_있고_큰_덩어리는_없다() throws Exception {
		String key = 키(); // 맨 앞에서 검사(header()에 넣기 전)
		ObjectMapper mapper = new ObjectMapper();
		String requestJson = mapper.writeValueAsString(java.util.Map.of("requests", java.util.List.of(java.util.Map.of(
				"image", java.util.Map.of("content", Base64.getEncoder().encodeToString(글자그림())),
				"features", java.util.List.of(java.util.Map.of("type", "DOCUMENT_TEXT_DETECTION"))))));
		HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/images:annotate"))
				.timeout(Duration.ofSeconds(20))
				.header("x-goog-api-key", key)
				.header("X-Goog-FieldMask", "responses.fullTextAnnotation.text,responses.error.code")
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestJson)).build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			assertThat(response.statusCode()).isEqualTo(200);
			JsonNode first = mapper.readTree(response.body()).path("responses").path(0);
			assertThat(first.path("fullTextAnnotation").path("text").asString()).isNotBlank();
			// 마스크가 먹었는지: pages(좌표 포함 큰 덩어리)가 없어야 한다. 이 줄이 실패하면 마스크가 무시된 것(동작엔 지장 없고 응답만 커짐).
			System.out.println("LIVE 응답 최상위 키: " + first.propertyNames());
			assertThat(first.path("fullTextAnnotation").has("pages")).as("FieldMask가 pages를 걸러야 함").isFalse();
			assertThat(first.has("textAnnotations")).as("FieldMask가 textAnnotations를 걸러야 함").isFalse();
		}
	}
}
