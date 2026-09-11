# =========================
# BUILD
# =========================
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -q -DskipTests package

# Find the generated jar automatically
RUN JAR_FILE=$(find /app/target -maxdepth 1 -type f -name "*.jar" ! -name "original-*" | head -n 1) \
    && echo "FOUND JAR: $JAR_FILE" \
    && cp "$JAR_FILE" /app/app.jar


# =========================
# RUNTIME
# =========================
FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y \
      openjdk-17-jre-headless \
      chromium \
      chromium-driver \
      ca-certificates \
      fonts-liberation \
      && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/app.jar /app/app.jar

ENV PORT=10000

EXPOSE 10000

CMD ["java", "-jar", "/app/app.jar"]
