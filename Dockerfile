# One image recipe for every Java service in this repo, picked by build argument:
#
#   docker build --build-arg SERVICE=auth-service -t tiktok/auth-service .     (make image SERVICE=…)
#
# SERVICE is a directory under services/ — its artifactId is the same name. rank-service and
# moderation-service are Python and keep their own Dockerfiles.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG SERVICE
# Only the chosen service and the libs it uses (-am). The Maven repository is a BuildKit cache, so
# the next service built reuses what this one downloaded.
RUN test -n "$SERVICE" || { echo "--build-arg SERVICE=<directory under services/> is required" >&2; exit 1; }
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -DskipTests -pl "services/${SERVICE}" -am package \
 && cp "services/${SERVICE}/target/${SERVICE}"-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER app
# Give every running replica its own SNOWFLAKE_NODE_ID (0-1023). Unset, each picks one at random:
# fine for a single replica, a 1-in-1024 chance of colliding ids for every pair beyond that — and
# Cassandra overwrites a comment with a colliding id silently.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
