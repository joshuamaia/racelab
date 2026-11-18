# syntax=docker/dockerfile:1
# RaceLab backend — build context: ./backend (+ named context "certs" = ./infrastructure/certs)

# ---- build ------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
# Optional extra CA certificates (TLS-intercepting proxies/antivirus); no-op when the folder has none.
COPY --from=certs . /tmp/certs/
RUN for c in /tmp/certs/*.crt; do [ -f "$c" ] && keytool -importcert -noprompt -cacerts -storepass changeit \
      -alias "extra-$(basename "$c")" -file "$c" || true; done
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package -DskipTests && cp target/racelab-backend.jar /workspace/app.jar

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S racelab && adduser -S racelab -G racelab
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER racelab
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SPRING_PROFILES_ACTIVE=docker
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
