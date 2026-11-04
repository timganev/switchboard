FROM eclipse-temurin:25-jre
# Links the published image to the repository on GitHub.
LABEL org.opencontainers.image.source=https://github.com/timganev/switchboard
ARG MODULE
COPY ${MODULE}/target/*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
