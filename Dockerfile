# ==============================
# BUILD
# ==============================
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests clean package

# Copy the generated shaded JAR regardless of artifact name.
RUN cp "$(find /app/target -maxdepth 1 -type f -name '*.jar' ! -name 'original-*' | head -n 1)" /app/app.jar


# ==============================
# RUNTIME
# ==============================
FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        chromium \
        chromium-driver \
        ca-certificates \
        fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

ENV CHROME_BIN=/usr/bin/chromium
ENV CHROMEDRIVER=/usr/bin/chromedriver
ENV PORT=10000

WORKDIR /app

COPY --from=build /app/app.jar /app/app.jar

# Keep the NUSI page files directly on disk as well as inside the JAR.
# This prevents the root page from returning "Not found" on Render.
COPY --from=build /app/src/main/resources/public /app/public

EXPOSE 10000

CMD ["java", "-Xms64m", "-Xmx320m", "-jar", "/app/app.jar"]
