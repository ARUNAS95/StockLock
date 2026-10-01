# ---- build ---------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Resolve dependencies in their own layer so source changes do not re-download them.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/stocklock-*.jar app.jar

# ---- runtime -------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S stocklock && adduser -S stocklock -G stocklock
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER stocklock
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
