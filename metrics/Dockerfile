FROM registry.access.redhat.com/ubi8/openjdk-17-runtime:1.20

ENV LANGUAGE='en_US:en'

COPY --chown=185 target/mcp-server-metrics-999-SNAPSHOT.jar /deployments/app.jar

EXPOSE 8080

ENV JAVA_OPTS_APPEND="-Dquarkus.http.host=0.0.0.0 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENV JAVA_APP_JAR="/deployments/app.jar"

ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
