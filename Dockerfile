# 1단계: Gradle 빌드 — wrapper를 써서 로컬과 동일한 Gradle 버전으로 빌드한다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# 의존성 레이어 캐시: 빌드 스크립트만 먼저 복사해 소스 변경 시 재다운로드를 피한다
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
RUN ./gradlew --no-daemon dependencies --quiet > /dev/null || true

COPY src/ src/
RUN ./gradlew --no-daemon bootJar

# 2단계: 실행 — JRE만 포함한 얇은 이미지. curl은 compose healthcheck용
FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar

ENV TZ=Asia/Seoul \
    JAVA_OPTS="-Xms128m -Xmx512m"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
