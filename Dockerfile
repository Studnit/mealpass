FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd -r -u 1001 app && mkdir -p /app/uploads && chown app /app/uploads
COPY --from=build /app/target/meal-token-system-*.jar app.jar
USER app
ENV UPLOAD_DIR=/app/uploads THYMELEAF_CACHE=true
# Persistent uploads: attach a Railway volume in the service settings, mount path /app/uploads.
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
