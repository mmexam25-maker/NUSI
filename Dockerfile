# ==============================
# BUILD
# ==============================

FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -q -DskipTests clean package

# Take the generated shaded JAR
RUN cp "$(find /app/target \
    -maxdepth 1 \
    -type f \
    -name '*.jar' \
    ! -name 'original-*' \
    | head -n 1)" /app/app.jar


# ==============================
# RUNTIME
# ==============================

FROM debian:bookworm-slim

# Java + Chromium + ChromeDriver
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        chromium \
        chromium-driver \
        ca-certificates \
        fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/app.jar /app/app.jar

EXPOSE 10000

ENV PORT=10000
ENV CHROME_BIN=/usr/bin/chromium
ENV CHROMEDRIVER=/usr/bin/chromedriver

CMD ["java", "-jar", "/app/app.jar"]
