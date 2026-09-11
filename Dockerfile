# ==============================
# BUILD
# ==============================
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests clean package

RUN cp "$(find /app/target -maxdepth 1 -type f -name '*.jar' ! -name 'original-*' | head -n 1)" /app/app.jar

# ==============================
# RUNTIME
# ==============================
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/app.jar /app/app.jar

ENV PORT=10000
EXPOSE 10000

CMD ["java", "--add-modules", "jdk.httpserver", "-Xms64m", "-Xmx256m", "-jar", "/app/app.jar"]
