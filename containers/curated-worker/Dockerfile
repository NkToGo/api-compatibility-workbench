# Official Eclipse Temurin 21 JDK, resolved from 21-jdk-jammy on 2026-09-22.
FROM eclipse-temurin:21-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438
COPY lib/ /opt/workbench/lib/
COPY case/ /opt/workbench/case/
COPY dependencies/ /opt/workbench/dependencies/
USER 65532:65532
WORKDIR /work
# The host controller supplies the fixed entry point and mandatory runtime policy.
ENTRYPOINT ["/usr/bin/false"]
