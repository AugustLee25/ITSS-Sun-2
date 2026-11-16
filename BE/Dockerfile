FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xmx320m" \
    SPRING_PROFILES_ACTIVE=demo
EXPOSE 8080
# Render cấp DB_HOST/DB_PORT/DB_NAME; ghép thành JDBC URL rồi chạy.
CMD ["sh", "-c", "export HANARE_DB_URL=\"jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}\" && exec java -jar app.jar --server.port=${PORT:-8080}"]
