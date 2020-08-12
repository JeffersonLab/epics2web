FROM gradle:5.5.1-jdk8 as builder

USER root
WORKDIR /

RUN git clone https://github.com/JeffersonLab/epics2web \
   && cd epics2web \
   && gradle build -x test

FROM 9.0.37-jdk11-adoptopenjdk-hotspot

COPY --from=builder /epics2web/build/libs /usr/local/tomcat/webapps