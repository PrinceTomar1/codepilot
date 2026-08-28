# --- Build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies separately from source changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# --- Runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /build/target/codepilot-backend.jar /app/app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=6 \
    CMD curl -f http://localhost:${PORT:-8080}/actuator/health || exit 1

ENV JAVA_OPTS=""
# -Dserver.port takes precedence over application.yml's server.port -- so on platforms like
# Railway that assign a dynamic port via $PORT, the app actually binds to it instead of always
# using the SERVER_PORT-driven default of 8080.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
