# ---- 빌드 스테이지: JDK + Gradle로 jar 생성 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 캐시를 위해 빌드 설정 파일을 먼저 복사
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 줄바꿈(CRLF) 안전장치 + 실행 권한
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src src
# 테스트는 로컬에서 이미 통과시킨 뒤 빌드하므로 이미지 빌드에서는 생략
RUN ./gradlew bootJar -x test --no-daemon

# ---- 실행 스테이지: JRE만 ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# root가 아닌 사용자로 실행
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
# 1GB EC2 대비: 컨테이너 메모리의 일부만 힙으로 사용
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
