# HR Pay: one image with the API and the web app. Build from the repository root:
#   docker build -t hr-pay:0.9.0 .
# See deploy/README.md for running it in production.

FROM node:25-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:27-jdk AS api
WORKDIR /src
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle gradle
# Downloads Gradle and the libraries once, so a code change does not download them again.
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY backend/src src
COPY --from=web /web/dist src/main/resources/static
RUN ./gradlew --no-daemon -q bootJar

FROM eclipse-temurin:27-jre
RUN groupadd --system hrpay && useradd --system --gid hrpay --home-dir /app --shell /usr/sbin/nologin hrpay \
    && mkdir -p /app && chown hrpay:hrpay /app
WORKDIR /app
COPY --from=api --chown=hrpay:hrpay /src/build/libs/hr-pay.jar app.jar
USER hrpay
EXPOSE 8081
ENV TZ=Africa/Kinshasa \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    FORWARD_HEADERS=native \
    COOKIE_SECURE=true \
    LOG_FORMAT=ecs
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health/liveness HTTP/1.0\\r\\n\\r\\n' >&3 && head -1 <&3 | grep -q ' 200 '"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
