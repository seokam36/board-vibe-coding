package com.example.demo.photo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import com.example.demo.photo.service.InvalidImageException.Reason;

// 스프링 없이 ImageProcessor만 검사하는 단위 테스트 (빠르다)
class ImageProcessorTests {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void 회전6과_GPS가_있는_큰_JPEG는_세로로_돌아가고_축소되고_GPS가_사라진다() throws Exception {
        // 3000x2000 가로 사진 + Orientation=6(시계 방향 90도 돌려서 봐야 함) + GPS 태그
        byte[] src = withExif(jpeg(solid(3000, 2000, Color.GRAY)), 6, true);
        // 입력에는 GPS가 실제로 들어 있어야 이 테스트가 의미 있다
        assertThat(readMetadata(src).getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();

        ProcessedImage result = processor.process(src);

        assertThat(result.main().width()).isEqualTo(1365);
        assertThat(result.main().height()).isEqualTo(2048);
        assertThat(result.main().format()).isEqualTo("JPEG");
        assertThat(result.main().contentType()).isEqualTo("image/jpeg");
        assertThat(Math.max(result.thumbnail().width(), result.thumbnail().height())).isEqualTo(320);
        assertThat(result.thumbnail().height()).isEqualTo(320);

        // 결과 바이트를 다시 읽어도 GPS·EXIF 회전 정보가 없어야 한다
        for (ProcessedImage.Variant v : new ProcessedImage.Variant[] {result.main(), result.thumbnail()}) {
            Metadata meta = readMetadata(v.data());
            assertThat(meta.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
            assertThat(meta.getFirstDirectoryOfType(ExifIFD0Directory.class)).isNull();
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(v.data()));
            assertThat(decoded.getWidth()).isEqualTo(v.width());
            assertThat(decoded.getHeight()).isEqualTo(v.height());
        }
    }

    // 원본 왼쪽 위에 빨간 블록을 그려 두고, 회전 후 빨간 블록이 어느 모서리로 갔는지 본다
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void EXIF_회전값_1부터_8까지_모두_반영한다(int orientation) throws Exception {
        BufferedImage img = solid(60, 40, Color.WHITE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 24, 24);
        g.dispose();

        ProcessedImage result = processor.process(withExif(jpeg(img), orientation, false));

        boolean swapped = orientation >= 5;
        assertThat(result.main().width()).isEqualTo(swapped ? 40 : 60);
        assertThat(result.main().height()).isEqualTo(swapped ? 60 : 40);

        // 빨간 블록의 기대 위치: 1,5=왼쪽 위 / 2,6=오른쪽 위 / 3,7=오른쪽 아래 / 4,8=왼쪽 아래
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.main().data()));
        int w = out.getWidth();
        int h = out.getHeight();
        int x = switch (orientation) {
            case 1, 5, 4, 8 -> 6;
            default -> w - 7;
        };
        int y = switch (orientation) {
            case 1, 5, 2, 6 -> 6;
            default -> h - 7;
        };
        Color c = new Color(out.getRGB(x, y));
        assertThat(c.getRed()).isGreaterThan(180);
        assertThat(c.getGreen()).isLessThan(100);
    }

    @Test
    void 투명_PNG는_PNG로_나오고_투명이_유지된다() throws Exception {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(200, 0, 200, 300); // 오른쪽 절반만 파랑, 왼쪽은 투명
        g.dispose();

        ProcessedImage result = processor.process(png(img));

        assertThat(result.main().format()).isEqualTo("PNG");
        assertThat(result.main().contentType()).isEqualTo("image/png");
        assertThat(result.thumbnail().format()).isEqualTo("PNG");
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.main().data()));
        assertThat(out.getColorModel().hasAlpha()).isTrue();
        assertThat((out.getRGB(50, 150) >>> 24) & 0xFF).isZero();       // 왼쪽: 투명
        assertThat((out.getRGB(350, 150) >>> 24) & 0xFF).isEqualTo(255); // 오른쪽: 불투명
    }

    @Test
    void 작은_이미지는_확대하지_않는다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(100, 50, Color.GREEN)));

        assertThat(result.main().width()).isEqualTo(100);
        assertThat(result.main().height()).isEqualTo(50);
        assertThat(result.thumbnail().width()).isEqualTo(100);
        assertThat(result.thumbnail().height()).isEqualTo(50);
    }

    @Test
    void 가짜_jpg_텍스트는_지원하지_않는_형식으로_거부한다() {
        byte[] fake = "이건 그림이 아니라 글자예요 .jpg".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> processor.process(fake))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_FORMAT));
    }

    @Test
    void GIF와_빈_파일은_지원하지_않는_형식으로_거부한다() {
        byte[] gif = "GIF89a".concat("xxxxxxxxxxxx").getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> processor.process(gif))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_FORMAT));
        assertThatThrownBy(() -> processor.process(new byte[0]))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_FORMAT));
    }

    @Test
    void 매직_바이트만_맞고_내용이_깨진_파일은_깨진_파일로_거부한다() {
        byte[] broken = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03, 0x04};

        assertThatThrownBy(() -> processor.process(broken))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.CORRUPTED));
    }

    @Test
    void 긴_변이_10000을_넘는_이미지는_헤더만_보고_거부한다() {
        // 실제 픽셀 데이터 없이 헤더에만 20000x100이라고 적은 PNG (디코딩했다면 실패했을 것)
        byte[] bomb = pngHeaderOnly(20_000, 100);

        assertThatThrownBy(() -> processor.process(bomb))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void 총_픽셀이_4천만을_넘는_이미지는_헤더만_보고_거부한다() {
        // 9000x5000 = 4500만 픽셀: 긴 변은 한도 안이지만 총 픽셀이 초과
        byte[] bomb = pngHeaderOnly(9_000, 5_000);

        assertThatThrownBy(() -> processor.process(bomb))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
    }

    // ---------- 보충 테스트 (완료 조건 2, 4, 5, 6) ----------

    // 조건 2 경계값: 한도 "정확히"는 통과해야 한다. 헤더만 있는 PNG라 상한 검사는 지나도
    // 디코딩에서 CORRUPTED로 실패한다. 즉 TOO_LARGE만 아니면 상한 검사를 통과한 것.
    @Test
    void 긴_변이_정확히_10000이면_크기_검사를_통과한다() {
        byte[] edge = pngHeaderOnly(10_000, 100);

        assertThatThrownBy(() -> processor.process(edge))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isNotEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void 긴_변이_10001이면_TOO_LARGE로_거부한다() {
        byte[] over = pngHeaderOnly(10_001, 1);

        assertThatThrownBy(() -> processor.process(over))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void 세로로_긴_변이_10001이어도_TOO_LARGE로_거부한다() {
        byte[] over = pngHeaderOnly(1, 10_001);

        assertThatThrownBy(() -> processor.process(over))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
    }

    @Test
    void 총_픽셀이_정확히_4천만이면_크기_검사를_통과한다() {
        byte[] edge = pngHeaderOnly(8_000, 5_000); // 8000 x 5000 = 40,000,000

        assertThatThrownBy(() -> processor.process(edge))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isNotEqualTo(Reason.TOO_LARGE));
    }

    // 긴 변은 한도 안(8001 <= 10000)인데 총 픽셀만 4천만을 살짝 넘는 경우: 총 픽셀 검사만의 경계
    @Test
    void 긴_변은_한도_안이고_총_픽셀만_4천만을_넘으면_TOO_LARGE로_거부한다() {
        byte[] over = pngHeaderOnly(8_001, 5_000); // 40,005,000

        assertThatThrownBy(() -> processor.process(over))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.TOO_LARGE));
    }

    // 실제 이미지로도 긴 변 정확히 10000은 처리되어 2048로 줄어든다
    @Test
    void 실제_10000x1_PNG는_통과하고_본_사진은_2048x1로_줄어든다() throws Exception {
        ProcessedImage result = processor.process(png(solid(10_000, 1, Color.BLUE)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(1);
    }

    // 조건 4: 세로로 긴 사진(회전 없음)도 긴 변(높이)이 2048 / 320이 된다
    @Test
    void 세로로_긴_2000x3000_사진은_1365x2048과_썸네일_213x320으로_줄어든다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(2000, 3000, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(1365);
        assertThat(result.main().height()).isEqualTo(2048);
        assertThat(result.thumbnail().width()).isEqualTo(213);
        assertThat(result.thumbnail().height()).isEqualTo(320);
    }

    @Test
    void 가로로_긴_3000x2000_사진은_2048x1365로_줄어든다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(3000, 2000, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(1365);
        assertThat(result.thumbnail().width()).isEqualTo(320);
        assertThat(result.thumbnail().height()).isEqualTo(213);
    }

    @Test
    void 긴_변이_정확히_2048이면_본_사진은_그대로이고_썸네일만_줄어든다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(2048, 1024, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(1024);
        assertThat(result.thumbnail().width()).isEqualTo(320);
        assertThat(result.thumbnail().height()).isEqualTo(160);
    }

    // 본 사진은 2048 아래인데 썸네일 기준(320)보다는 큰 경우: 본 사진은 그대로, 썸네일만 축소
    @Test
    void 중간_크기_사진은_본_사진은_그대로고_썸네일만_320으로_줄어든다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(800, 400, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(800);
        assertThat(result.main().height()).isEqualTo(400);
        assertThat(result.thumbnail().width()).isEqualTo(320);
        assertThat(result.thumbnail().height()).isEqualTo(160);
    }

    // 조건 5: 형식 유지. JPEG는 본/썸네일 모두 JPEG(실제 바이트도 FF D8 FF)
    @Test
    void JPEG_입력은_본_사진과_썸네일_모두_JPEG으로_나온다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(1000, 500, Color.GRAY)));

        for (ProcessedImage.Variant v : new ProcessedImage.Variant[] {result.main(), result.thumbnail()}) {
            assertThat(v.format()).isEqualTo("JPEG");
            assertThat(v.contentType()).isEqualTo("image/jpeg");
            assertThat(v.data()[0] & 0xFF).isEqualTo(0xFF);
            assertThat(v.data()[1] & 0xFF).isEqualTo(0xD8);
            assertThat(v.data()[2] & 0xFF).isEqualTo(0xFF);
        }
    }

    // PNG는 본/썸네일 모두 PNG(실제 바이트도 PNG 시그니처)이고 큰 사진은 줄어든다
    @Test
    void PNG_입력은_본_사진과_썸네일_모두_PNG로_나온다() throws Exception {
        ProcessedImage result = processor.process(png(solid(3000, 1000, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(683);
        for (ProcessedImage.Variant v : new ProcessedImage.Variant[] {result.main(), result.thumbnail()}) {
            assertThat(v.format()).isEqualTo("PNG");
            assertThat(v.contentType()).isEqualTo("image/png");
            assertThat(v.data()[0] & 0xFF).isEqualTo(0x89);
            assertThat(v.data()[1]).isEqualTo((byte) 'P');
            assertThat(v.data()[2]).isEqualTo((byte) 'N');
            assertThat(v.data()[3]).isEqualTo((byte) 'G');
        }
        assertThat(result.thumbnail().width()).isEqualTo(320);
    }

    // 조건 5: 큰 투명 PNG를 줄여도 썸네일에 알파가 남는다
    @Test
    void 큰_투명_PNG의_썸네일도_투명이_유지된다() throws Exception {
        BufferedImage img = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(500, 0, 500, 500); // 오른쪽 절반만 파랑, 왼쪽은 투명
        g.dispose();

        ProcessedImage result = processor.process(png(img));

        ProcessedImage.Variant t = result.thumbnail();
        assertThat(t.width()).isEqualTo(320);
        assertThat(t.height()).isEqualTo(160);
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(t.data()));
        assertThat(out.getColorModel().hasAlpha()).isTrue();
        assertThat((out.getRGB(40, 80) >>> 24) & 0xFF).isZero();         // 왼쪽: 투명
        assertThat((out.getRGB(280, 80) >>> 24) & 0xFF).isEqualTo(255);  // 오른쪽: 불투명
    }

    // 회전 값이 5~8이면 썸네일도 돌아간 방향(세로)이어야 한다
    @Test
    void 회전6이면_썸네일도_세로_방향이다() throws Exception {
        ProcessedImage result = processor.process(withExif(jpeg(solid(1000, 500, Color.GRAY)), 6, false));

        assertThat(result.main().width()).isEqualTo(500);
        assertThat(result.main().height()).isEqualTo(1000);
        assertThat(result.thumbnail().width()).isEqualTo(160);
        assertThat(result.thumbnail().height()).isEqualTo(320);
    }

    // 조건 1: PNG 시그니처가 8바이트 중 일부만 맞으면 PNG로 보지 않는다
    @Test
    void PNG_시그니처가_일부만_맞으면_지원하지_않는_형식이다() {
        byte[] almost = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x00, 1, 2, 3};

        assertThatThrownBy(() -> processor.process(almost))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_FORMAT));
    }

    // FF D8 까지만 맞고 세 번째 바이트가 FF가 아니면 JPEG이 아니다
    @Test
    void JPEG_매직_바이트_세_번째가_다르면_지원하지_않는_형식이다() {
        byte[] almost = {(byte) 0xFF, (byte) 0xD8, 0x00, 1, 2, 3};

        assertThatThrownBy(() -> processor.process(almost))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_FORMAT));
    }

    // 확장자·Content-Type이 아니라 내용으로 판단: WebP 모양(RIFF....WEBP)은 거부
    @Test
    void WebP_모양의_파일은_지원하지_않는_형식으로_거부한다() {
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 xxxxxxxx".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> processor.process(webp))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.UNSUPPORTED_FORMAT));
    }

    // 조건 6: 중간에 잘린 진짜 PNG는 CORRUPTED
    // (잘린 JPEG는 ImageIO가 남는 부분을 회색으로 채워 통과시킨다. 알려진 한계라 테스트하지 않는다)
    @Test
    void 중간이_잘린_PNG는_깨진_파일로_거부한다() throws Exception {
        byte[] full = png(noisy(400, 300));
        byte[] cut = java.util.Arrays.copyOf(full, full.length / 3);

        assertThatThrownBy(() -> processor.process(cut))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.CORRUPTED));
    }

    @Test
    void PNG_시그니처만_있고_내용이_없으면_깨진_파일로_거부한다() {
        byte[] sigOnly = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

        assertThatThrownBy(() -> processor.process(sigOnly))
                .isInstanceOfSatisfying(InvalidImageException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.CORRUPTED));
    }

    // ---------- 큰 사진 건너뛰며 읽기 (S13-g1 메모리 절약) ----------

    // 건너뛰는 간격은 올림: 읽은 결과 긴 변이 4096 이하가 되어야 한다
    @ParameterizedTest
    @CsvSource({"100,1", "4096,1", "4097,2", "8000,2", "8192,2", "8193,3", "10000,3"})
    void 건너뛰는_간격은_긴_변_기준_올림으로_정한다(int longSide, int expected) {
        assertThat(ImageProcessor.subsamplingFor(longSide, 100)).isEqualTo(expected);
        assertThat(ImageProcessor.subsamplingFor(100, longSide)).isEqualTo(expected);
    }

    @Test
    void 실제_8000x5000_JPEG는_건너뛰며_읽어도_2048x1280으로_나온다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(8000, 5000, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(1280);
        assertThat(result.thumbnail().width()).isEqualTo(320);
        assertThat(result.thumbnail().height()).isEqualTo(200);
    }

    @Test
    void 실제_8000x5000_PNG는_건너뛰며_읽어도_2048x1280으로_나온다() throws Exception {
        ProcessedImage result = processor.process(png(solid(8000, 5000, Color.BLUE)));

        assertThat(result.main().format()).isEqualTo("PNG");
        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(1280);
    }

    // n=3으로 읽는 경우(긴 변 9000): 나눠떨어지지 않아도 원본 비율 기준 크기가 나온다
    @Test
    void 실제_9000x4000_JPEG는_2048x910으로_나온다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(9000, 4000, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(910); // 4000 * 2048/9000 = 910.2
    }

    @Test
    void 회전6인_큰_8000x5000_JPEG는_세로_1280x2048로_나온다() throws Exception {
        ProcessedImage result = processor.process(withExif(jpeg(solid(8000, 5000, Color.GRAY)), 6, false));

        assertThat(result.main().width()).isEqualTo(1280);
        assertThat(result.main().height()).isEqualTo(2048);
    }

    // 실제로 건너뛰며 읽는지 지키는 테스트: 디코딩 직후 이미지 크기를 본다 (n=2면 4000x2500)
    @Test
    void 디코딩_단계에서_8000x5000_JPEG는_4000x2500만_읽고_원본_크기는_기억한다() throws Exception {
        ImageProcessor.Decoded d = processor.decode(jpeg(solid(8000, 5000, Color.GRAY)), "JPEG");

        assertThat(d.image().getWidth()).isEqualTo(4000);
        assertThat(d.image().getHeight()).isEqualTo(2500);
        assertThat(d.origWidth()).isEqualTo(8000);
        assertThat(d.origHeight()).isEqualTo(5000);
    }

    @Test
    void 디코딩_단계에서_8000x5000_PNG는_4000x2500만_읽고_원본_크기는_기억한다() throws Exception {
        ImageProcessor.Decoded d = processor.decode(png(solid(8000, 5000, Color.BLUE)), "PNG");

        assertThat(d.image().getWidth()).isEqualTo(4000);
        assertThat(d.image().getHeight()).isEqualTo(2500);
        assertThat(d.origWidth()).isEqualTo(8000);
        assertThat(d.origHeight()).isEqualTo(5000);
    }

    @Test
    void 디코딩_단계에서_3000x2000_작은_사진은_건너뛰지_않고_그대로_읽는다() throws Exception {
        ImageProcessor.Decoded d = processor.decode(jpeg(solid(3000, 2000, Color.GRAY)), "JPEG");

        assertThat(d.image().getWidth()).isEqualTo(3000);
        assertThat(d.image().getHeight()).isEqualTo(2000);
        assertThat(d.origWidth()).isEqualTo(3000);
        assertThat(d.origHeight()).isEqualTo(2000);
    }

    // ---------- 큰 사진 건너뛰며 읽기 보충 (재검사) ----------

    // 세로로 긴 큰 사진: n=2 -> 디코딩 2500x4000, 최종은 높이 기준 2048
    @Test
    void 디코딩_단계에서_세로_5000x8000_JPEG는_2500x4000만_읽는다() throws Exception {
        ImageProcessor.Decoded d = processor.decode(jpeg(solid(5000, 8000, Color.GRAY)), "JPEG");

        assertThat(d.image().getWidth()).isEqualTo(2500);
        assertThat(d.image().getHeight()).isEqualTo(4000);
        assertThat(d.origWidth()).isEqualTo(5000);
        assertThat(d.origHeight()).isEqualTo(8000);
    }

    @Test
    void 실제_세로_5000x8000_JPEG는_1280x2048과_썸네일_200x320으로_나온다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(5000, 8000, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(1280);
        assertThat(result.main().height()).isEqualTo(2048);
        assertThat(result.thumbnail().width()).isEqualTo(200);
        assertThat(result.thumbnail().height()).isEqualTo(320);
    }

    // 긴 변 정확히 10000(가로 10000x4000 = 정확히 4천만 픽셀) 실제 이미지: n=3, 4000x4000 이하로 읽힌다
    // (메모리를 아끼려고 흑백 이미지로 만든다)
    @Test
    void 디코딩_단계에서_실제_10000x4000_PNG는_n3으로_읽고_긴_변이_4096_이하다() throws Exception {
        ImageProcessor.Decoded d = processor.decode(png(gray(10_000, 4_000)), "PNG");

        assertThat(d.origWidth()).isEqualTo(10_000);
        assertThat(d.origHeight()).isEqualTo(4_000);
        assertThat(d.image().getWidth()).isEqualTo(3334); // 10000 / 3 올림
        assertThat(d.image().getHeight()).isEqualTo(1334); // 4000 / 3 올림
        assertThat(Math.max(d.image().getWidth(), d.image().getHeight())).isLessThanOrEqualTo(4096);
    }

    @Test
    void 실제_10000x4000_PNG는_정상_처리되어_2048x819로_나온다() throws Exception {
        ProcessedImage result = processor.process(png(gray(10_000, 4_000)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(819); // 4000 * 2048/10000 = 819.2
        assertThat(result.thumbnail().width()).isEqualTo(320);
        assertThat(result.thumbnail().height()).isEqualTo(128);
    }

    // 짧은 변이 매우 작아도 건너뛰기 때문에 0이 되면 안 된다 (4097x100, n=2)
    @Test
    void 디코딩_단계에서_4097x100_JPEG는_n2로_읽어도_짧은_변이_0이_아니다() throws Exception {
        ImageProcessor.Decoded d = processor.decode(jpeg(solid(4097, 100, Color.GRAY)), "JPEG");

        assertThat(d.image().getWidth()).isEqualTo(2049); // 4097 / 2 올림
        assertThat(d.image().getHeight()).isEqualTo(50);
        assertThat(d.origWidth()).isEqualTo(4097);
        assertThat(d.origHeight()).isEqualTo(100);
    }

    @Test
    void 실제_4097x100_JPEG의_최종_크기는_2048x50이다() throws Exception {
        ProcessedImage result = processor.process(jpeg(solid(4097, 100, Color.GRAY)));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(50); // 100 * 2048/4097 = 49.99
    }

    // 짧은 변이 1px인 극단: 건너뛰어도 높이가 1 이상이어야 한다 (10000x1)
    @Test
    void 디코딩_단계에서_실제_10000x1_PNG도_높이가_1_이상이다() throws Exception {
        ImageProcessor.Decoded d = processor.decode(png(solid(10_000, 1, Color.BLUE)), "PNG");

        assertThat(d.image().getHeight()).isGreaterThanOrEqualTo(1);
        assertThat(d.image().getWidth()).isLessThanOrEqualTo(4096);
    }

    // 큰 투명 PNG(n=2로 읽음)도 알파가 유지된다 (본 사진·썸네일 모두)
    @Test
    void 건너뛰며_읽는_큰_투명_PNG도_알파가_유지된다() throws Exception {
        BufferedImage img = new BufferedImage(6000, 3000, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(3000, 0, 3000, 3000); // 오른쪽 절반만 파랑, 왼쪽은 투명
        g.dispose();

        ProcessedImage result = processor.process(png(img));

        assertThat(result.main().width()).isEqualTo(2048);
        assertThat(result.main().height()).isEqualTo(1024);
        for (ProcessedImage.Variant v : new ProcessedImage.Variant[] {result.main(), result.thumbnail()}) {
            assertThat(v.format()).isEqualTo("PNG");
            BufferedImage out = ImageIO.read(new ByteArrayInputStream(v.data()));
            assertThat(out.getColorModel().hasAlpha()).isTrue();
            int y = out.getHeight() / 2;
            assertThat((out.getRGB(out.getWidth() / 4, y) >>> 24) & 0xFF).isZero();          // 왼쪽: 투명
            assertThat((out.getRGB(out.getWidth() * 3 / 4, y) >>> 24) & 0xFF).isEqualTo(255); // 오른쪽: 불투명
        }
    }

    // ---------- 테스트용 도구 ----------

    // 메모리를 아끼려고 흑백(바이트 1개/픽셀)으로 만든 이미지
    private static BufferedImage gray(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    // 압축이 잘 안 되도록 무작위 색으로 채운 이미지 (잘린 파일 테스트용, 시드 고정)
    private static BufferedImage noisy(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.util.Random rnd = new java.util.Random(42);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, rnd.nextInt(0x1000000));
            }
        }
        return img;
    }

    private static BufferedImage solid(int w, int h, Color color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static Metadata readMetadata(byte[] data) throws Exception {
        return ImageMetadataReader.readMetadata(new ByteArrayInputStream(data));
    }

    // JPEG 바로 뒤(SOI 다음)에 EXIF(APP1) 조각을 직접 조립해서 끼운다. 의존성 없이 바이트로만 만든다.
    // 구조: FFE1 + 길이 + "Exif\0\0" + TIFF(헤더, IFD0[Orientation, (GPS 가리키는 포인터)], GPS IFD)
    private static byte[] withExif(byte[] jpeg, int orientation, boolean withGps) {
        int ifd0Entries = withGps ? 2 : 1;
        int ifd0Size = 2 + ifd0Entries * 12 + 4;
        int gpsOffset = 8 + ifd0Size;

        ByteBuffer tiff = ByteBuffer.allocate(8 + ifd0Size + (withGps ? 2 + 2 * 12 + 4 : 0)); // 빅엔디안
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 0x002A).putInt(8);
        // IFD0
        tiff.putShort((short) ifd0Entries);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0); // Orientation
        if (withGps) {
            tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsOffset); // GPS IFD 위치
        }
        tiff.putInt(0); // 다음 IFD 없음
        if (withGps) {
            tiff.putShort((short) 2);
            tiff.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0); // 위도 방향
            tiff.putShort((short) 0x0003).putShort((short) 2).putInt(2).put((byte) 'E').put((byte) 0).putShort((short) 0); // 경도 방향
            tiff.putInt(0);
        }

        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int segLen = 2 + exifHeader.length + tiff.capacity();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI (FF D8)
        out.write(0xFF);
        out.write(0xE1);
        out.write(segLen >> 8);
        out.write(segLen & 0xFF);
        out.writeBytes(exifHeader);
        out.writeBytes(tiff.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    // 시그니처 + IHDR(가로·세로만 적음) + 빈 IDAT + IEND 로 이뤄진 PNG. 픽셀 데이터가 없어서 풀면 실패하지만 헤더는 읽힌다.
    private static byte[] pngHeaderOnly(int w, int h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", new byte[0]);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
