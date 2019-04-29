FROM openjdk:8-jre

WORKDIR /opt/notary

COPY btc-dw-bridge/build/libs/btc-dw-bridge-all.jar /opt/notary/btc-dw-bridge.jar

## Wait for script (see https://github.com/ufoscout/docker-compose-wait/)

ADD https://github.com/ufoscout/docker-compose-wait/releases/download/2.5.0/wait /opt/notary/wait
RUN chmod +x /opt/notary/wait
CMD /opt/notary/wait && java -jar /opt/notary/btc-dw-bridge.jar
