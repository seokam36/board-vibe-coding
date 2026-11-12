package com.example.demo.photo.repository;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

// S3 연결(S3Client)을 만드는 설정 (S13-g6-3). app.photo.storage=s3 일 때만 켜진다.
// 액세스 키는 쓰지 않는다: EC2에 붙은 IAM 역할을 SDK 기본 자격 증명 체인이 알아서 찾는다.
// 설정이 잘못됐으면(버킷·리전 비었음, 버킷에 못 닿음) 앱이 시작 때 바로 실패해서, 운영 중에 사진이 조용히 안 올라가는 일을 막는다.
@Configuration
@ConditionalOnProperty(name = "app.photo.storage", havingValue = "s3")
public class S3ClientConfig {

    // 연결 풀 크기: open()은 응답 스트림이 끝날 때까지 S3 연결 1개를 붙잡는다.
    // SDK 기본(50)은 Tomcat 요청 스레드(기본 200)보다 적어 사진 보기가 몰리면 줄을 선다.
    // 사진 요청이 전체의 일부라는 점과 작은 서버의 소켓·메모리를 같이 고려해 스레드의 절반인 100으로 정했다.
    static final int MAX_CONNECTIONS = 100;
    // 풀이 꽉 찼을 때 연결을 기다리는 최대 시간: 기본(10초)이면 요청 스레드가 오래 묶이므로 3초 후 포기(500)한다.
    static final Duration CONNECTION_ACQUISITION_TIMEOUT = Duration.ofSeconds(3);
    // S3에 연결(TCP) 맺는 시간
    static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);
    // 소켓 읽기·쓰기 "무응답" 제한: 전체 시간이 아니라 데이터가 한 번 오고 다음 데이터까지 멈춘 시간이다.
    // 사진 보기 스트림도 이 값의 적용을 받으므로, 느리게 받는 사용자는 괜찮고(바이트가 계속 흐르면 안 끊김) 멈춰 버린 연결만 끊는다.
    static final Duration SOCKET_TIMEOUT = Duration.ofSeconds(10);
    // 한 번 시도의 제한 / 재시도까지 포함한 호출 전체 제한.
    // 확인한 SDK 동작: apiCall(Attempt)Timeout은 "응답 머리를 받고 메서드가 돌아올 때"까지만 재는 타이머다.
    // getObject(...)가 스트림을 돌려준 뒤 스트림을 읽는 시간에는 적용되지 않는다(타이머가 그 시점에 취소됨).
    // 그래서 큰 사진을 천천히 받아도 이 값 때문에 잘리지 않고, put(최대 10MB 업로드)에만 영향을 준다.
    // 10MB 업로드가 느린 회선에서도 끝날 수 있게 시도 30초 / 전체 60초로 넉넉히 잡는다.
    static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(60);

    @Bean(destroyMethod = "close")
    public S3Client s3Client(@Value("${app.photo.s3.bucket:}") String bucket,
                             @Value("${app.photo.s3.region:}") String region) {
        // 이름이 비었으면 SDK에 넘기기 전에 분명한 메시지로 시작을 막는다
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(
                    "app.photo.storage=s3 인데 버킷 이름이 없습니다. 환경변수 PHOTO_S3_BUCKET을 설정하세요.");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "app.photo.storage=s3 인데 리전이 없습니다. 환경변수 PHOTO_S3_REGION을 설정하세요 (예: ap-northeast-2).");
        }

        S3Client client = createClient(region.trim());

        // 시작 때 버킷에 실제로 닿는지(이름·리전·권한) 한 번 확인. 실패하면 만든 클라이언트를 닫고 시작 실패
        try {
            verifyBucket(client, bucket.trim());
        } catch (RuntimeException e) {
            client.close();
            throw e;
        }
        return client;
    }

    // "클라이언트 만들기"만 따로 뺀 메서드(HeadBucket과 분리). 만드는 것만으로는 네트워크를 타지 않아 테스트에서 직접 만들어 볼 수 있다.
    static S3Client createClient(String region) {
        return S3Client.builder()
                .region(Region.of(region))
                // credentialsProvider를 따로 정하지 않으면 기본 체인(환경변수 -> ... -> EC2 IAM 역할)을 쓴다
                .httpClientBuilder(Apache5HttpClient.builder()
                        .maxConnections(MAX_CONNECTIONS)
                        .connectionAcquisitionTimeout(CONNECTION_ACQUISITION_TIMEOUT)
                        .connectionTimeout(CONNECTION_TIMEOUT)
                        .socketTimeout(SOCKET_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build())
                .build();
    }

    // HeadBucket = "이 버킷이 있고 내가 접근할 수 있나?" 가벼운 확인 요청
    static void verifyBucket(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "S3 버킷에 접근할 수 없습니다. 버킷 이름·리전·IAM 권한을 확인하세요: " + e.getMessage(), e);
        }
    }
}
