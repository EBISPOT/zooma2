
FROM maven:3.9.6-amazoncorretto-21

COPY . /opt/
RUN cd /opt/ && ls && mvn clean package

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/opt/target/zooma2-1.0-SNAPSHOT.jar"]


