package com.example.demo.photo.service;

import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.springframework.stereotype.Service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.example.demo.photo.service.InvalidImageException.Reason;

// 업로드된 사진을 검사하고 다듬는 부품 (S13-g1). 순서:
// 매직 바이트로 형식 판별 -> 헤더만 읽어 픽셀 상한 검사 -> 디코딩 -> 축소 -> EXIF 회전 반영 -> 재인코딩.
// 재인코딩하면서 메타데이터를 쓰지 않으므로 GPS 같은 EXIF가 사라진다.
// 동시 처리 수 제한(Semaphore)은 이 클래스가 아니라 호출하는 서비스(S13-g2)에서 건다.
@Service
public class ImageProcessor {

    static final int MAIN_MAX_SIDE = 2048;       // 본 사진 긴 변 최대
    static final int THUMB_MAX_SIDE = 320;       // 썸네일 긴 변 최대
    static final int MAX_SIDE_LIMIT = 10_000;    // 이보다 긴 변은 거부
    static final long MAX_PIXELS = 40_000_000L;  // 총 픽셀이 이보다 많으면 거부
    static final float JPEG_QUALITY = 0.85f;

    private static final String JPEG = "JPEG";
    private static final String PNG = "PNG";

    // 업로드 바이트 하나를 받아 본 사진 + 썸네일을 만든다. 잘못된 입력이면 InvalidImageException.
    public ProcessedImage process(byte[] input) {
        if (input == null) {
            throw new InvalidImageException(Reason.CORRUPTED, "빈 파일입니다");
        }
        String format = detectFormat(input);

        Decoded decoded = decode(input, format);
        // EXIF 회전 정보는 JPEG에서 읽는다. 없거나 읽기 실패면 1(그대로)
        int orientation = JPEG.equals(format) ? readOrientation(input) : 1;

        // 먼저 줄이고 나서 회전한다(회전해도 긴 변 길이는 같으니 결과가 같고, 큰 이미지를 돌리는 비용을 아낀다)
        // 목표 크기는 "원본" 가로·세로 기준으로 계산한다(건너뛰며 읽은 이미지는 크기가 살짝 달라질 수 있어서)
        BufferedImage main = orient(
                shrinkToFit(decoded.image(), MAIN_MAX_SIDE, decoded.origWidth(), decoded.origHeight()), orientation);
        BufferedImage thumb = shrinkToFit(main, THUMB_MAX_SIDE);

        return new ProcessedImage(encode(main, format), encode(thumb, format));
    }

    // 파일 맨 앞 몇 바이트(매직 바이트)로만 형식을 판단한다. 확장자·Content-Type은 믿지 않는다.
    static String detectFormat(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length >= png.length) {
            boolean same = true;
            for (int i = 0; i < png.length; i++) {
                if (b[i] != png[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return PNG;
            }
        }
        throw new InvalidImageException(Reason.UNSUPPORTED_FORMAT, "JPEG, PNG 사진만 올릴 수 있습니다");
    }

    // 디코딩 결과 + 원본 크기(건너뛰며 읽으면 image는 원본보다 작을 수 있다)
    // 패키지 전용: 테스트에서 디코딩된 크기를 확인하려고 연다
    record Decoded(BufferedImage image, int origWidth, int origHeight) {
    }

    // 건너뛰며 읽을 간격 n. 읽은 결과의 긴 변이 MAIN_MAX_SIDE*2(4096) 이하가 되게 올림으로 정한다.
    // 어차피 2048로 줄일 거라 처음부터 덜 읽어 메모리를 아끼는 것. 4096 이하면 1(전부 읽음).
    static int subsamplingFor(int w, int h) {
        int longSide = Math.max(w, h);
        int target = MAIN_MAX_SIDE * 2;
        return Math.max(1, (longSide + target - 1) / target);
    }

    // 헤더로 크기부터 확인한 뒤(큰 이미지를 통째로 풀기 전에 거절) 디코딩한다.
    // 패키지 전용: 테스트에서 디코딩 크기(건너뛰며 읽었는지) 확인용
    Decoded decode(byte[] input, String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
        if (!readers.hasNext()) {
            throw new InvalidImageException(Reason.UNSUPPORTED_FORMAT, "이 형식을 읽을 수 없습니다");
        }
        ImageReader reader = readers.next();
        // 임시 파일을 만들지 않도록 메모리 캐시 스트림을 쓴다
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            reader.setInput(in, true, true);
            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            if (w <= 0 || h <= 0) {
                throw new InvalidImageException(Reason.CORRUPTED, "사진 크기를 읽을 수 없습니다");
            }
            if (w > MAX_SIDE_LIMIT || h > MAX_SIDE_LIMIT || (long) w * h > MAX_PIXELS) {
                throw new InvalidImageException(Reason.TOO_LARGE, "사진이 너무 큽니다");
            }
            // 큰 사진은 n픽셀마다 하나씩만 읽는다(디코딩 단계에서 건너뜀). 통째로 읽으면 동시 업로드 때 메모리가 모자랄 수 있다
            int n = subsamplingFor(w, h);
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(n, n, 0, 0);
            BufferedImage img = reader.read(0, param);
            if (img == null) {
                throw new InvalidImageException(Reason.CORRUPTED, "사진을 읽을 수 없습니다");
            }
            return new Decoded(img, w, h);
        } catch (IOException | RuntimeException e) {
            if (e instanceof InvalidImageException ex) {
                throw ex;
            }
            // 깨진 파일, 지원 안 하는 색 모델(CMYK 등)은 모두 "읽을 수 없는 사진"으로 본다
            throw new InvalidImageException(Reason.CORRUPTED, "사진을 읽을 수 없습니다", e);
        } finally {
            reader.dispose();
        }
    }

    // EXIF Orientation(1~8). 태그가 없거나 읽기 실패면 1
    private int readOrientation(byte[] input) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(input));
            ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir != null && dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                int o = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
                if (o >= 1 && o <= 8) {
                    return o;
                }
            }
        } catch (Exception e) {
            // 회전 정보를 못 읽어도 업로드 자체는 막지 않는다
        }
        return 1;
    }

    // 긴 변이 maxSide 이하가 되게 줄인다. 원본이 더 작으면 그대로(확대 안 함).
    private BufferedImage shrinkToFit(BufferedImage src, int maxSide) {
        return shrinkToFit(src, maxSide, src.getWidth(), src.getHeight());
    }

    // origW/origH: 줄이기 전 "원본" 크기. 목표 크기는 이 값으로 계산하고, src는 그 크기로 다시 그린다.
    private BufferedImage shrinkToFit(BufferedImage src, int maxSide, int origW, int origH) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longSide = Math.max(origW, origH); // 회전해도 긴 변 길이는 같다
        boolean alpha = src.getColorModel().hasAlpha();
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        if (longSide <= maxSide) {
            // 크기는 그대로. 다만 JPEG 재인코딩을 위해 알파 없는 RGB 등으로 형태를 통일한다
            return redraw(src, w, h, type, false);
        }
        double scale = (double) maxSide / longSide;
        int tw = Math.max(1, (int) Math.round(origW * scale));
        int th = Math.max(1, (int) Math.round(origH * scale));

        // 단계적 축소: 한 번에 크게 줄이면 거칠어지므로 절반씩 줄이다 마지막에 목표 크기로 맞춘다
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) {
            cur = redraw(cur, cur.getWidth() / 2, cur.getHeight() / 2, type, false);
        }
        return redraw(cur, tw, th, type, true);
    }

    // 지정한 크기·타입의 새 이미지에 고품질 설정으로 다시 그린다
    private BufferedImage redraw(BufferedImage src, int w, int h, int type, boolean bicubic) {
        BufferedImage dst = new BufferedImage(w, h, type);
        var g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    bicubic ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    // EXIF Orientation 값(1~8)에 맞게 회전/뒤집기. 5~8은 가로·세로가 바뀐다.
    // 변환식: x' = m00*x + m01*y + m02, y' = m10*x + m11*y + m12
    private BufferedImage orient(BufferedImage src, int orientation) {
        if (orientation == 1) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        AffineTransform t = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);   // 좌우 뒤집기
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);  // 180도
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);   // 상하 뒤집기
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);    // 대각선 뒤집기(transpose)
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);   // 시계 방향 90도
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);  // 반대 대각선 뒤집기(transverse)
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);   // 반시계 방향 90도
            default -> new AffineTransform();
        };
        boolean swap = orientation >= 5;
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, src.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var g = dst.createGraphics();
        try {
            g.drawImage(src, t, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    // 원래 형식 그대로 다시 저장한다. JPEG: 품질 0.85, 메타데이터 없음(알파 없는 RGB). PNG: 알파 유지.
    private ProcessedImage.Variant encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (JPEG.equals(format)) {
                writeJpeg(img, out);
                return new ProcessedImage.Variant(out.toByteArray(), JPEG, "image/jpeg", img.getWidth(), img.getHeight());
            }
            if (!ImageIO.write(img, "png", out)) {
                throw new IOException("PNG 쓰기 도구가 없습니다");
            }
            return new ProcessedImage.Variant(out.toByteArray(), PNG, "image/png", img.getWidth(), img.getHeight());
        } catch (IOException e) {
            throw new IllegalStateException("사진 저장 형식으로 변환하지 못했습니다", e);
        }
    }

    private void writeJpeg(BufferedImage img, ByteArrayOutputStream out) throws IOException {
        // JPEG은 알파 채널을 못 쓰므로 알파가 있으면 흰 배경 RGB로 바꾼다
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(ios);
            // 메타데이터(두 번째 null)를 주지 않으므로 EXIF·GPS가 들어가지 않는다
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
