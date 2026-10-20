# CampusHire API image. Build context is the repository root (bm4a/platform is built first, then backend):
#   docker build -f backend/Dockerfile -t campushire-backend:local .
FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY bm4a/platform/pom.xml bm4a/platform/pom.xml
COPY backend/pom.xml backend/pom.xml
COPY bm4a/platform/src bm4a/platform/src
COPY backend/src backend/src

# Reactor build of both modules; the Maven repository is a BuildKit cache shared across builds.
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -q -DskipTests package

# Split the Spring Boot jar into layers so dependency layers are reused when only code changes.
RUN java -Djarmode=tools -jar backend/target/campushire.jar extract --layers --launcher --destination /workspace/extracted


FROM eclipse-temurin:25-jre

RUN groupadd --system --gid 10001 campushire \
    && useradd --system --uid 10001 --gid campushire --no-create-home --shell /usr/sbin/nologin campushire

WORKDIR /app

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
