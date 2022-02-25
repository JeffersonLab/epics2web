FROM gradle:7.4-jdk17 as builder

ARG CUSTOM_CRT_URL

USER root
WORKDIR /

RUN git clone https://github.com/JeffersonLab/epics2web \
    && if [ -z "$CUSTOM_CRT_URL" ] ; then echo "No custom cert needed"; else \
          wget -O /usr/local/share/ca-certificates/customcert.crt $CUSTOM_CRT_URL \
          && update-ca-certificates \
          && keytool -import -alias custom -file /usr/local/share/ca-certificates/customcert.crt -storetype JKS -keystore $JAVA_HOME/jre/lib/security/cacerts -storepass changeit -noprompt \
          && export OPTIONAL_CERT_ARG=-Djavax.net.ssl.trustStore=$JAVA_HOME/jre/lib/security/cacerts \
          ; fi \
    && cd epics2web \
    && gradle build $OPTIONAL_CERT_ARG -x test


FROM tomcat:9.0.39-jdk8-adoptopenjdk-hotspot

COPY --from=builder /epics2web/build/libs /usr/local/tomcat/webapps