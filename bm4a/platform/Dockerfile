FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -DskipTests package


FROM eclipse-temurin:25-jre

RUN groupadd --system --gid 10001 bm4a \
    && useradd --system --uid 10001 --gid bm4a --no-create-home --shell /usr/sbin/nologin bm4a

WORKDIR /app

COPY --from=build /workspace/target/bm4a-platform-exec.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
