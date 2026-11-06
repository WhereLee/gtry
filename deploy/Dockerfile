FROM eclipse-temurin:17-jre

WORKDIR /app
COPY app/helpdesk.jar /app/helpdesk.jar

EXPOSE 8091

ENTRYPOINT ["java", "-Xmx768m", "-XX:+UseG1GC", "-jar", "/app/helpdesk.jar"]
