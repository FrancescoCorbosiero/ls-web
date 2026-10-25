# Logistica Sanchez web app: one image for staging and production (ADR 0001, ADR 0022).
# Build:  docker build -t ghcr.io/francescocorbosiero/ls-web:dev .
# Run:    see deploy/compose.yml; every setting comes from the environment (deploy/.env.example).
# CI builds this image only after the unit, integration and E2E jobs of the same commit passed,
# so the build stage packages the jar without running the tests again.

# ---- build ---------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build
# Wrapper and pom first: the dependency download is cached while sources change.
COPY mvnw pom.xml ./
# Windows checkouts may turn mvnw into CRLF (shebang "bash\r" -> "not found"): normalise it.
RUN sed -i "s/\r$//" mvnw && chmod +x mvnw
COPY .mvn .mvn
RUN ./mvnw -B -ntp -q dependency:go-offline || true
COPY src src
RUN ./mvnw -B -ntp -q -DskipTests package \
    && cp target/ls-web-*.jar /build/app.jar

# ---- runtime -------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble
# DejaVu fonts for the PDF report (OpenPDF embeds them), curl for the HEALTHCHECK.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-dejavu-core curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar /app/app.jar
USER app
ENV TZ=Europe/Rome \
    SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${SERVER_PORT}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
