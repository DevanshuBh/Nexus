# One backend image with every service jar; docker compose picks the service with `command`.
# Multi-arch: build with `docker buildx build --platform linux/amd64,linux/arm64` for the Arm (Ampere) VM.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY nexus-common/pom.xml nexus-common/
COPY nexus-simulator/pom.xml nexus-simulator/
COPY nexus-ingest/pom.xml nexus-ingest/
COPY nexus-writer/pom.xml nexus-writer/
COPY nexus-query/pom.xml nexus-query/
COPY nexus-gateway/pom.xml nexus-gateway/
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline -DexcludeArtifactIds=nexus-common || true
COPY . .
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN ./mvnw -B -q package -DskipTests \
 && mkdir /out \
 && for m in simulator ingest writer query gateway; do cp nexus-$m/target/nexus-$m-*.jar /out/nexus-$m.jar; done

FROM eclipse-temurin:25-jre
RUN groupadd --system nexus && useradd --system --gid nexus --home /app nexus
WORKDIR /app
COPY --from=build /out/*.jar /app/
USER nexus
# Container-aware heap; each service gets a memory limit in compose.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
CMD ["java", "-jar", "/app/nexus-ingest.jar"]
