# syntax=docker/dockerfile:1
# Java 25 backend runtime image (STORY-INFRA-006). Build (context is backend/):
#   docker build -t gw2-backend backend
# Ordinary base images: the Eclipse Temurin official Docker Hub images, JDK for the build stage and
# JRE for the runtime stage, both on the Java 25 LTS line the project compiles for (maven.compiler.release=25).
# The tag follows Temurin's patch releases; pass --build-arg to pin a digest for a bit-exact rebuild.
ARG JDK_IMAGE=eclipse-temurin:25-jdk
ARG JRE_IMAGE=eclipse-temurin:25-jre

FROM ${JDK_IMAGE} AS build
WORKDIR /src
# Dependencies first so a source-only change reuses the downloaded Maven repository layer.
COPY .mvn/ .mvn/
COPY pom.xml ./
COPY --chmod=755 mvnw ./
# Existing Windows checkouts may still contain CRLF even after the repository attributes change.
RUN sed -i 's/\r$//' mvnw && ./mvnw -B -q dependency:resolve
COPY src/ src/
# The pom deliberately has no repackage execution (ordinary `mvn package` keeps a plain jar), so the
# executable Spring Boot jar is produced by invoking the pinned plugin goal explicitly. Tests run in CI.
RUN ./mvnw -B -q -DskipTests package spring-boot:repackage

FROM ${JRE_IMAGE}
RUN useradd --system --uid 10001 --user-group --home-dir /app --shell /usr/sbin/nologin gw2 \
 && mkdir -p /app/data/icons && chown -R gw2:gw2 /app
COPY --from=build --chown=gw2:gw2 /src/target/gw2-tool.jar /app/gw2-tool.jar
USER gw2
# The working directory is the writable data location: the crafting graph cache file is cwd-relative.
WORKDIR /app/data
# Icon cache boundary stays configurable through ICON_CACHE_DIR; this is its container default.
ENV ICON_CACHE_DIR=/app/data/icons
# DATABASE_URL, DATABASE_USER and DATABASE_PASSWORD are required at start; GW2_API_KEY is not.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gw2-tool.jar"]
