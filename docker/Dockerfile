FROM bellsoft/liberica-runtime-container:jdk-21-glibc
ARG JAR_FILE=tripweather-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
