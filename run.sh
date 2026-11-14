#!/usr/bin/env bash
#
# Copyright the jpa-bench authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Runs the benchmark matrix runner x database x dataset, strictly one run after another, and writes
#
#   results/<timestamp>/metadata.json                        versions, JDK, OS, CPU, DB image, seed, git commit
#   results/<timestamp>/<runner>-<db>-<dataset>.json         JMH results (-rf json), modes thrpt + avgt
#   results/<timestamp>/<runner>-<db>-bootstrap.json         emfBootstrap, mode ss
#   results/<timestamp>/sql/<runner>-<db>-<dataset>/*.sql    distinct SQL of the first iteration per benchmark
#   results/<timestamp>/logs/*.log                           complete JMH console output
#
# Run ./run.sh --help for the options.

set -euo pipefail

cd "$(dirname "$0")"

ALL_RUNNERS="runner-openjpa-4.1 runner-openjpa-4.2 runner-hibernate-6.6 runner-hibernate-7 runner-eclipselink-4 runner-eclipselink-5"

RUNNERS="$ALL_RUNNERS"
DBS="h2 postgresql"
DATASETS="S"
INCLUDE='ReadBenchmarks|WriteBenchmarks'
FORKS=2
WARMUP_ITERATIONS=5
ITERATIONS=5
WARMUP_TIME=10s
TIME=10s
BOOTSTRAP_WARMUP=5
BOOTSTRAP_ITERATIONS=20
BOOTSTRAP=true
BUILD=true
JVM_ARGS=""
OUT_ROOT="results"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

usage() {
    cat <<EOF
Usage: ./run.sh [options]

Matrix (space or comma separated lists):
  --runners LIST          runner modules (default: all six)
                          $ALL_RUNNERS
  --dbs LIST              h2 postgresql (default: both)
  --datasets LIST         S M L (default: S)
  --include REGEX         JMH benchmark regex (default: $INCLUDE)
  --no-bootstrap          skip the separate emfBootstrap run

JMH settings:
  --forks N               forks per benchmark (default: $FORKS)
  --warmup-iterations N   (default: $WARMUP_ITERATIONS)
  --iterations N          (default: $ITERATIONS)
  --warmup-time T         (default: $WARMUP_TIME)
  --time T                measurement iteration time (default: $TIME)
  --bootstrap-iterations N  measured emfBootstrap shots per fork (default: $BOOTSTRAP_ITERATIONS)
  --jvm-args "ARGS"       replaces the fork JVM arguments (JMH -jvmArgs; default from @Fork(jvmArgs): -Xms2g -Xmx2g)
  --smoke                 1 fork, 1 warmup + 1 measurement iteration of 1s (checks that everything runs)

Other:
  --skip-build            use the existing <runner>/target/benchmarks.jar
  --out DIR               results root (default: $OUT_ROOT)
  -h, --help

PostgreSQL runs in a Testcontainers container (image, CPU and memory limits: see README.md),
so Docker must be available. Set JPABENCH_PG_URL to use an existing server instead.
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --runners) RUNNERS="${2//,/ }"; shift 2 ;;
        --dbs) DBS="${2//,/ }"; shift 2 ;;
        --datasets) DATASETS="${2//,/ }"; shift 2 ;;
        --include) INCLUDE="$2"; shift 2 ;;
        --no-bootstrap) BOOTSTRAP=false; shift ;;
        --forks) FORKS="$2"; shift 2 ;;
        --warmup-iterations) WARMUP_ITERATIONS="$2"; shift 2 ;;
        --iterations) ITERATIONS="$2"; shift 2 ;;
        --warmup-time) WARMUP_TIME="$2"; shift 2 ;;
        --time) TIME="$2"; shift 2 ;;
        --bootstrap-iterations) BOOTSTRAP_ITERATIONS="$2"; shift 2 ;;
        --jvm-args) JVM_ARGS="$2"; shift 2 ;;
        --smoke)
            FORKS=1; WARMUP_ITERATIONS=1; ITERATIONS=1; WARMUP_TIME=1s; TIME=1s
            BOOTSTRAP_WARMUP=1; BOOTSTRAP_ITERATIONS=1
            shift ;;
        --skip-build) BUILD=false; shift ;;
        --out) OUT_ROOT="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

log() {
    echo "[run.sh $(date -u +%H:%M:%S)] $*"
}

json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | tr -d '\n\r'
}

# --- preconditions -------------------------------------------------------------------------------
for db in $DBS; do
    case "$db" in
        h2) ;;
        postgresql)
            if [ -z "${JPABENCH_PG_URL:-}" ] && ! docker info > /dev/null 2>&1; then
                echo "PostgreSQL runs in Docker, but 'docker info' fails. Start Docker (and set DOCKER_HOST if needed)." >&2
                exit 1
            fi ;;
        *) echo "Unknown database: $db" >&2; exit 2 ;;
    esac
done
for dataset in $DATASETS; do
    case "$dataset" in
        S|M|L) ;;
        *) echo "Unknown dataset: $dataset (S, M or L)" >&2; exit 2 ;;
    esac
done

if [ "$BUILD" = true ]; then
    modules=$(echo "$RUNNERS" | tr ' ' ',')
    log "building $modules"
    ./mvnw -B -q package -DskipTests -pl "$modules" -am
fi

for runner in $RUNNERS; do
    if [ ! -f "$runner/target/benchmarks.jar" ]; then
        echo "Missing $runner/target/benchmarks.jar (is the runner implemented? build with ./mvnw -B package)" >&2
        exit 1
    fi
done

# --- metadata ------------------------------------------------------------------------------------
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$OUT_ROOT"
OUT="$(cd "$OUT_ROOT" && pwd)/$TIMESTAMP"
mkdir -p "$OUT/logs" "$OUT/sql"

case "$(uname -s)" in
    Darwin)
        CPU=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)
        CORES=$(sysctl -n hw.ncpu 2>/dev/null || echo unknown)
        MEMORY_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo 0) ;;
    Linux)
        CPU=$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//' || echo unknown)
        CORES=$(nproc 2>/dev/null || echo unknown)
        MEMORY_BYTES=$(awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo 2>/dev/null || echo 0) ;;
    *)
        CPU=unknown; CORES=unknown; MEMORY_BYTES=0 ;;
esac
GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo unknown)
GIT_DIRTY=false
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    GIT_DIRTY=true
fi
JAVA_VERSION=$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.runtime.version/ {print $2}')
JAVA_VENDOR=$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.vendor = / {print $2}')
DOCKER_VERSION=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo none)

{
    echo "{"
    echo "  \"timestamp\": \"$TIMESTAMP\","
    echo "  \"git\": {\"commit\": \"$GIT_COMMIT\", \"dirty\": $GIT_DIRTY},"
    echo "  \"java\": {\"version\": \"$(json_escape "$JAVA_VERSION")\", \"vendor\": \"$(json_escape "$JAVA_VENDOR")\"},"
    echo "  \"os\": {\"name\": \"$(uname -s)\", \"release\": \"$(uname -r)\", \"arch\": \"$(uname -m)\"},"
    echo "  \"cpu\": {\"model\": \"$(json_escape "$CPU")\", \"cores\": \"$CORES\", \"memoryBytes\": $MEMORY_BYTES},"
    echo "  \"docker\": \"$(json_escape "$DOCKER_VERSION")\","
    echo "  \"postgres\": {\"image\": \"${JPABENCH_PG_IMAGE:-postgres:18.1}\", \"cpus\": ${JPABENCH_PG_CPUS:-2}, \"memoryMb\": ${JPABENCH_PG_MEMORY_MB:-2048}, \"external\": \"$(json_escape "${JPABENCH_PG_URL:-}")\"},"
    echo "  \"matrix\": {\"runners\": \"$RUNNERS\", \"dbs\": \"$DBS\", \"datasets\": \"$DATASETS\", \"include\": \"$(json_escape "$INCLUDE")\"},"
    echo "  \"jmh\": {\"modes\": \"thrpt,avgt\", \"forks\": $FORKS, \"warmupIterations\": $WARMUP_ITERATIONS, \"iterations\": $ITERATIONS, \"warmupTime\": \"$WARMUP_TIME\", \"time\": \"$TIME\", \"bootstrapWarmup\": $BOOTSTRAP_WARMUP, \"bootstrapIterations\": $BOOTSTRAP_ITERATIONS, \"jvmArgs\": \"$(json_escape "$JVM_ARGS")\"},"
    echo "  \"runners\": {"
    first=true
    for runner in $RUNNERS; do
        if [ "$first" = true ]; then first=false; else echo ","; fi
        printf '    "%s": ' "$runner"
        "$JAVA_BIN" -cp "$runner/target/benchmarks.jar" io.github.rzo1.jpabench.core.ProviderInfo h2 | sed '2,$s/^/    /' | tr -d '\r'
    done
    echo ""
    echo "  }"
    echo "}"
} > "$OUT/metadata.json"
log "results go to $OUT"

# --- benchmark matrix ----------------------------------------------------------------------------
JMH_COMMON=(-f "$FORKS" -foe true)
if [ -n "$JVM_ARGS" ]; then
    JMH_COMMON+=(-jvmArgs "$JVM_ARGS")
fi

for runner in $RUNNERS; do
    jar="$runner/target/benchmarks.jar"
    for db in $DBS; do
        for dataset in $DATASETS; do
            name="$runner-$db-$dataset"
            log "$name"
            JPABENCH_SQL_DIR="$OUT/sql/$name" "$JAVA_BIN" -jar "$jar" "$INCLUDE" -e emfBootstrap \
                "${JMH_COMMON[@]}" -bm thrpt,avgt -tu ms \
                -wi "$WARMUP_ITERATIONS" -i "$ITERATIONS" -w "$WARMUP_TIME" -r "$TIME" \
                -p db="$db" -p dataset="$dataset" \
                -rf json -rff "$OUT/$name.json" 2>&1 | tee "$OUT/logs/$name.log"
        done
        if [ "$BOOTSTRAP" = true ]; then
            name="$runner-$db-bootstrap"
            log "$name"
            JPABENCH_SQL_DIR="$OUT/sql/$name" "$JAVA_BIN" -jar "$jar" 'BootstrapBenchmark' \
                "${JMH_COMMON[@]}" -bm ss -tu ms \
                -wi "$BOOTSTRAP_WARMUP" -i "$BOOTSTRAP_ITERATIONS" \
                -p db="$db" \
                -rf json -rff "$OUT/$name.json" 2>&1 | tee "$OUT/logs/$name.log"
        fi
    done
done

log "done: $OUT"
