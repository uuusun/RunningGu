FROM eclipse-temurin:21-jdk@sha256:a16bfc04b28f66c1d27218ffeefc8ae6a2621de423e01597156ba813ca5fa668 AS normalizer-build

ADD --chmod=0444 --checksum=sha256:b59c024afe172ec6ec85b6327006c3138ec58c7d0bcd26253d0e42853f613def \
    https://repo1.maven.org/maven2/com/graphhopper/graphhopper-web/11.0/graphhopper-web-11.0.jar \
    /opt/graphhopper/graphhopper-web.jar
COPY scripts/osm/import/ImportConfigNormalizer.java /src/ImportConfigNormalizer.java
RUN javac \
    -cp /opt/graphhopper/graphhopper-web.jar \
    -d /opt/runninggu/normalizer \
    /src/ImportConfigNormalizer.java

FROM eclipse-temurin:21-jre@sha256:d1d7f43094ea37e9ae77ea3ca40026bc1de1bf1dc2cad37b08d66b9a016a2ea6 AS java-runtime

FROM python:3.12-slim@sha256:2fe5997d249a808b8eeea52c58a1dbffbba28754dc11699ef5c029f2d818ce79

ARG GRAPHHOPPER_VERSION=11.0
ARG GRAPHHOPPER_JAR_SHA256=b59c024afe172ec6ec85b6327006c3138ec58c7d0bcd26253d0e42853f613def

LABEL org.runninggu.graphhopper.version="${GRAPHHOPPER_VERSION}" \
      org.runninggu.graphhopper.jar-sha256="${GRAPHHOPPER_JAR_SHA256}" \
      org.runninggu.graphhopper.role="builder"

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY --from=normalizer-build /opt/graphhopper/graphhopper-web.jar /opt/graphhopper/graphhopper-web.jar
COPY --from=normalizer-build /opt/runninggu/normalizer /opt/runninggu/normalizer
COPY --chmod=0555 scripts/osm/import/run-builder.sh /opt/runninggu/import/run-builder.sh
COPY --chmod=0555 scripts/osm/import/package-graph.sh /opt/runninggu/import/package-graph.sh
COPY --chmod=0555 scripts/osm/import/verify-artifact.sh /opt/runninggu/import/verify-artifact.sh
COPY --chmod=0444 scripts/osm/import/artifact_contract.py /opt/runninggu/import/artifact_contract.py
COPY --chmod=0444 scripts/osm/import/graphhopper-import.yml /opt/runninggu/import/graphhopper-import.yml
COPY --chmod=0555 scripts/osm/import/normalize-import-config.sh /opt/runninggu/import/normalize-import-config.sh

ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:$PATH \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    RUNNINGGU_IMPORT_CONFIG_NORMALIZER=/opt/runninggu/import/normalize-import-config.sh

WORKDIR /work
ENTRYPOINT ["/opt/runninggu/import/run-builder.sh"]
