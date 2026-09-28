FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon help

COPY src ./src
RUN ./gradlew --no-daemon bootJar && \
    find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \; && \
    test -s /workspace/app.jar

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin shareguard
COPY --from=build --chown=shareguard:shareguard /workspace/app.jar /app/app.jar
USER shareguard

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
