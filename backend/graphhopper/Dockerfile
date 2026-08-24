FROM eclipse-temurin:21-jre

ADD --chmod=0444 https://github.com/graphhopper/graphhopper/releases/download/11.0/graphhopper-web-11.0.jar /opt/graphhopper/graphhopper-web.jar
COPY --chmod=0444 graphhopper.yml /opt/graphhopper/graphhopper.yml

RUN useradd --system --uid 10001 --home-dir /opt/graphhopper graphhopper \
    && mkdir -p /data/graph-cache /data/srtm-cache \
    && chown -R graphhopper:graphhopper /data

USER graphhopper
WORKDIR /data
EXPOSE 8989

ENTRYPOINT ["java", "-Xms1g", "-Xmx6g", "-jar", "/opt/graphhopper/graphhopper-web.jar", "server", "/opt/graphhopper/graphhopper.yml"]
