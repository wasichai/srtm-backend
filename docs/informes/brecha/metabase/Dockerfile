# Metabase with the community DuckDB driver baked in (srtm-backend#206, task 9). Plan § Rulings: Metabase
# 0.63.19 + driver 1.5.5.0 ("Metabase 63 + DuckDB 1.5.5") on eclipse-temurin:21-jre-jammy - the driver does not
# run on the official Alpine image - with both jar sha256 checked at build, never at runtime, so a tampered or
# mismatched download fails the build instead of silently loading. Mirrors the driver's own release Dockerfile
# (motherduckdb/metabase_duckdb_driver, tag 1.5.5.0) for the base image, plugins dir and CMD.
#
#   METABASE_ORG=<org> docker compose -f metabase/compose.yml --env-file ~/.config/srtm-analitica/<org>.env build
#
FROM eclipse-temurin:21-jre-jammy

ARG METABASE_VERSION=0.63.19
# computed once: curl -fsSL -o metabase.jar https://downloads.metabase.com/v0.63.19/metabase.jar && shasum -a 256 metabase.jar
ARG METABASE_SHA256=d4695f2d4b0e0ba79f37d39b2a1ae9937d1e133aff813c33c0772a6329b2af02
ARG DUCKDB_DRIVER_VERSION=1.5.5.0
# published with the GitHub release itself (motherduckdb/metabase_duckdb_driver, tag 1.5.5.0); plan § Global
# Constraints
ARG DUCKDB_DRIVER_SHA256=47a0d41ca52ff82a6ce78b0c52b2bdb58b32094405e446734cefc44eb1ddbd07

RUN groupadd -r metabase && useradd -r -g metabase -m -d /home/metabase metabase

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /home/metabase

RUN curl -fsSL -o metabase.jar "https://downloads.metabase.com/v${METABASE_VERSION}/metabase.jar" \
    && echo "${METABASE_SHA256}  metabase.jar" | sha256sum -c -

RUN mkdir -p plugins \
    && curl -fsSL -o plugins/duckdb.metabase-driver.jar \
       "https://github.com/motherduckdb/metabase_duckdb_driver/releases/download/${DUCKDB_DRIVER_VERSION}/duckdb.metabase-driver.jar" \
    && echo "${DUCKDB_DRIVER_SHA256}  plugins/duckdb.metabase-driver.jar" | sha256sum -c -

RUN chown -R metabase:metabase /home/metabase

ENV MB_PLUGINS_DIR=/home/metabase/plugins

EXPOSE 3000
USER metabase

CMD ["java", "-jar", "/home/metabase/metabase.jar"]
