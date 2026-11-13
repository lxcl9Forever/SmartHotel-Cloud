FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080 8081 8082 8083 8084
ENTRYPOINT ["java","-Duser.timezone=Asia/Shanghai","-jar","/app/app.jar"]
