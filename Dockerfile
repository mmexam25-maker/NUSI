# ==============================
# BUILD
# ==============================

FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -q -DskipTests package

RUN cp "$(find /app/target -maxdepth 1 -type f -name '*.jar' ! -name 'original-*' | head -n 1)" /app/app.jar


# ==============================
# RUNTIME
# ==============================

FROM eclipse-temurin:17-jre

RUN apt-get update && \
    apt-get install -y \
        chromium \
        chromium-driver \
        ca-certificates \
        fonts-liberation \
        libnss3 \
        libgbm1 \
        libasound2 \
        libatk-bridge2.0-0 \
        libgtk-3-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/app.jar /app/app.jar

# COPY WEBSITE FILES ALSO
COPY --from=build /app/src/main/resources/public /app/public

EXPOSE 10000

ENV PORT=10000

CMD ["java", "-jar", "/app/app.jar"]
