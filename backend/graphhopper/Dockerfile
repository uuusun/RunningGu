FROM eclipse-temurin:21-jre@sha256:d1d7f43094ea37e9ae77ea3ca40026bc1de1bf1dc2cad37b08d66b9a016a2ea6

ARG GRAPHHOPPER_VERSION=11.0
ARG GRAPHHOPPER_JAR_SHA256=b59c024afe172ec6ec85b6327006c3138ec58c7d0bcd26253d0e42853f613def

LABEL org.runninggu.graphhopper.version="${GRAPHHOPPER_VERSION}" \
      org.runninggu.graphhopper.jar-sha256="${GRAPHHOPPER_JAR_SHA256}"

RUN install -d -m 0755 /opt/graphhopper
ADD --chmod=0444 --checksum=sha256:b59c024afe172ec6ec85b6327006c3138ec58c7d0bcd26253d0e42853f613def \
    https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/11.0/graphhopper-web-11.0.jar \
    /opt/graphhopper/graphhopper-web.jar
COPY --chmod=0444 graphhopper.yml /opt/graphhopper/graphhopper-local.yml
COPY --chmod=0444 graphhopper-server.yml /opt/graphhopper/graphhopper-server.yml

RUN chmod 0555 /opt/graphhopper \
    && groupadd --gid 10001 graphhopper \
    && useradd --system --uid 10001 --gid 10001 --home-dir /opt/graphhopper graphhopper \
    && mkdir -p /data/graph-cache /data/srtm-cache \
    && chown -R graphhopper:graphhopper /data

USER graphhopper
WORKDIR /data
EXPOSE 8989

ENTRYPOINT ["java"]
