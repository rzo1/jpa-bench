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
# Runs the matrix runner x database x dataset, one job after another, and writes results/<timestamp>/:
#
#   metadata.json                          machine, versions, JDK, OS, DB image, git commit, settings per runner
#   <runner>-<db>-<dataset>.json           JMH results: reads thrpt + avgt, writes ss with a fixed batch
#   metrics/<runner>-<db>-<dataset>.json   short metered run with the statement metrics (--metering separate)
#   <runner>-<db>-bootstrap.json           emfBootstrap warm
#   <runner>-<db>-coldstart.json           emfBootstrap cold: first shot of fresh JVMs
#   sql/<runner>-<db>-<dataset>/*.sql      distinct SQL of the first iteration per benchmark
#   logs/*.log                             JMH console output
#   execution.tsv                          jobs in execution order, with timestamps and status
#   verification.tsv                       result checksums of the read operations (equal for all runners)
#   datasets.tsv                           loaded dataset per runner, db and dataset: seed, checksum, row counts
#
# ./run.sh --help lists the options. Requires bash 3.2 or later.

set -euo pipefail

cd "$(dirname "$0")"

ALL_RUNNERS="runner-openjpa-4.1 runner-openjpa-4.2 runner-openjpa-4.1-agent runner-openjpa-4.2-agent runner-hibernate-6.6 runner-hibernate-7 runner-hibernate-6.6-enhanced runner-hibernate-7-enhanced runner-eclipselink-4 runner-eclipselink-5 runner-eclipselink-4-woven runner-eclipselink-5-woven"
TAB=$(printf '\t')

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
COLD_FORKS=10
BOOTSTRAP=true
METERING=separate
METER_FORKS=1
METER_WARMUP_ITERATIONS=1
METER_ITERATIONS=2
METER_TIME=2s
ORDER=sequential
SEED=""
BUILD=true
PROFILES=""
JVM_ARGS=""
OUT_ROOT="results"
SMOKE=false
CONTRIBUTOR=""
MACHINE_LABEL=""
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

usage() {
    cat <<EOF
Usage: ./run.sh [options]

Matrix (space or comma separated lists):
  --runners LIST          runner modules (default: all)
                          $ALL_RUNNERS
  --dbs LIST              h2 postgresql (default: both)
  --datasets LIST         S M L (default: S)
  --include REGEX         JMH benchmark regex (default: $INCLUDE)
  --no-bootstrap          skip the emfBootstrap runs (warm and cold start)

JMH settings:
  --forks N               forks per benchmark (default: $FORKS)
  --warmup-iterations N   (default: $WARMUP_ITERATIONS)
  --iterations N          (default: $ITERATIONS)
  --warmup-time T         (default: $WARMUP_TIME; read benchmarks only)
  --time T                measurement iteration time (default: $TIME; read benchmarks only, write
                          benchmarks run single shot with a fixed batch per iteration)
  --bootstrap-iterations N  measured warm emfBootstrap shots per fork, after $BOOTSTRAP_WARMUP warmup shots
                          (default: $BOOTSTRAP_ITERATIONS)
  --cold-forks N          forks of the cold start run, one unwarmed emfBootstrap shot each (default: $COLD_FORKS)
  --jvm-args "ARGS"       replaces the fork JVM arguments (default: -Xms2g -Xmx2g); runners with a
                          target/javaagent.jar always get -javaagent:<jar> appended
  --smoke                 1 fork, 1 warmup + 1 measurement iteration of 1s; recorded, not comparable

Statement metering:
  --metering MODE         separate (default): timed unmetered, metrics from a short metered run
                                    ($METER_FORKS fork, $METER_WARMUP_ITERATIONS warmup + $METER_ITERATIONS iterations of $METER_TIME)
                          inline:   one metered run (metering overhead is timed)
                          off:      no statement metrics

Order:
  --order MODE            sequential (default): runner by runner
                          interleave: per kind, database and dataset all runners back to back
                          shuffle: random order of all jobs
  --seed N                seed of --order shuffle (default: random, recorded)

Build:
  --profiles LIST         Maven profiles, e.g. enhance (Hibernate), weave (EclipseLink)
  --skip-build            use the existing <runner>/target/benchmarks.jar

Recorded in metadata.json:
  --contributor NAME      your GitHub handle
  --machine LABEL         short machine label, e.g. "MacBook Pro M4 Max"

Other:
  --out DIR               results root (default: $OUT_ROOT)
  -h, --help

PostgreSQL runs in Docker (Testcontainers); JPABENCH_PG_URL selects an existing server instead.
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
        --cold-forks) COLD_FORKS="$2"; shift 2 ;;
        --jvm-args) JVM_ARGS="$2"; shift 2 ;;
        --smoke)
            FORKS=1; WARMUP_ITERATIONS=1; ITERATIONS=1; WARMUP_TIME=1s; TIME=1s
            BOOTSTRAP_WARMUP=1; BOOTSTRAP_ITERATIONS=1; COLD_FORKS=1
            METER_WARMUP_ITERATIONS=1; METER_ITERATIONS=1; METER_TIME=1s
            SMOKE=true
            shift ;;
        --metering) METERING="$2"; shift 2 ;;
        --order) ORDER="$2"; shift 2 ;;
        --seed) SEED="$2"; shift 2 ;;
        --profiles) PROFILES="${2// /,}"; shift 2 ;;
        --skip-build) BUILD=false; shift ;;
        --out) OUT_ROOT="$2"; shift 2 ;;
        --contributor) CONTRIBUTOR="$2"; shift 2 ;;
        --machine) MACHINE_LABEL="$2"; shift 2 ;;
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
case "$METERING" in
    separate|inline|off) ;;
    *) echo "Unknown metering mode: $METERING (separate, inline or off)" >&2; exit 2 ;;
esac
case "$ORDER" in
    sequential|interleave|shuffle) ;;
    *) echo "Unknown order: $ORDER (sequential, interleave or shuffle)" >&2; exit 2 ;;
esac
if [ "$ORDER" = shuffle ] && [ -z "$SEED" ]; then
    SEED=$(( (RANDOM << 15) | RANDOM ))
fi

if [ "$BUILD" = true ]; then
    modules=$(echo "$RUNNERS" | tr ' ' ',')
    log "building $modules${PROFILES:+ with profiles $PROFILES}"
    # clean: no enhanced or woven classes of an earlier build may survive
    if [ -n "$PROFILES" ]; then
        ./mvnw -B -q clean package -DskipTests -pl "$modules" -am -P "$PROFILES"
    else
        ./mvnw -B -q clean package -DskipTests -pl "$modules" -am
    fi
fi

for runner in $RUNNERS; do
    if [ ! -f "$runner/target/benchmarks.jar" ]; then
        echo "Missing $runner/target/benchmarks.jar (build with ./mvnw -B package)" >&2
        exit 1
    fi
done

# --- metadata ------------------------------------------------------------------------------------
TIMESTAMP=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p "$OUT_ROOT"
OUT="$(cd "$OUT_ROOT" && pwd)/$TIMESTAMP"
mkdir -p "$OUT/logs" "$OUT/sql"

# Machine description; every probe may fail and leaves the field empty.
first_line() {
    head -n 1 2>/dev/null | tr -d '\r' || true
}
MODEL=""; MODEL_ID=""; VENDOR=""; CPU=unknown; CORES=unknown; CORE_TYPES=""; MEMORY_BYTES=0
OS_PRETTY=""; POWER=""; BATTERY=""; LOW_POWER=""; GOVERNOR=""
case "$(uname -s)" in
    Darwin)
        VENDOR=Apple
        MODEL_ID=$(sysctl -n hw.model 2>/dev/null || true)
        CPU=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)
        CORES=$(sysctl -n hw.ncpu 2>/dev/null || echo unknown)
        MEMORY_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo 0)
        # model name and chip only (system_profiler also prints serial numbers)
        HW=$(system_profiler SPHardwareDataType 2>/dev/null || true)
        MODEL=$(echo "$HW" | sed -n 's/^ *Model Name: //p' | first_line || true)
        CHIP=$(echo "$HW" | sed -n 's/^ *Chip: //p' | first_line || true)
        if [ -n "$CHIP" ]; then
            CPU=$CHIP
        fi
        # core clusters, e.g. "4 Performance + 6 Efficiency"
        levels=$(sysctl -n hw.nperflevels 2>/dev/null || echo 0)
        level=0
        while [ "$level" -lt "$levels" ]; do
            n=$(sysctl -n "hw.perflevel$level.physicalcpu" 2>/dev/null || true)
            name=$(sysctl -n "hw.perflevel$level.name" 2>/dev/null || true)
            CORE_TYPES="$CORE_TYPES${CORE_TYPES:+ + }$n $name"
            level=$((level + 1))
        done
        OS_PRETTY="macOS $(sw_vers -productVersion 2>/dev/null || true) ($(sw_vers -buildVersion 2>/dev/null || true))"
        BATT=$(pmset -g batt 2>/dev/null || true)
        POWER=$(echo "$BATT" | sed -n "s/.*drawing from '\(.*\)'.*/\1/p" | first_line || true)
        BATTERY=$(echo "$BATT" | sed -n 's/.*InternalBattery.*[[:space:]]\([0-9][0-9]*%; [^;]*\).*/\1/p' | first_line || true)
        # powermode 1 / lowpowermode 1: Low Power Mode
        LOW_POWER=$(pmset -g 2>/dev/null | awk '$1 == "lowpowermode" || $1 == "powermode" { print ($2 == 1 ? "true" : "false"); exit }' || true) ;;
    Linux)
        VENDOR=$(first_line 2>/dev/null < /sys/class/dmi/id/sys_vendor || true)
        MODEL=$(first_line 2>/dev/null < /sys/class/dmi/id/product_name || true)
        CPU=$(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | sed 's/^ *//' || true)
        CPU=${CPU:-unknown}
        CORES=$(nproc 2>/dev/null || echo unknown)
        MEMORY_BYTES=$(awk '/MemTotal/ {print $2 * 1024}' /proc/meminfo 2>/dev/null || echo 0)
        OS_PRETTY=$(sed -n 's/^PRETTY_NAME="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' /etc/os-release 2>/dev/null | first_line || true)
        GOVERNOR=$(first_line 2>/dev/null < /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor || true)
        for supply in /sys/class/power_supply/*; do
            if [ -f "$supply/online" ] && [ "$(first_line 2>/dev/null < "$supply/type" || true)" = Mains ]; then
                if [ "$(first_line < "$supply/online")" = 1 ]; then POWER="AC Power"; else POWER="Battery Power"; fi
            fi
        done ;;
esac
MEMORY_BYTES=${MEMORY_BYTES:-0}
GIT_COMMIT=$(git rev-parse HEAD 2>/dev/null || echo unknown)
GIT_DIRTY=false
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
    GIT_DIRTY=true
fi
JAVA_VERSION=$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.runtime.version/ {print $2}')
JAVA_VENDOR=$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.vendor = / {print $2}')
DOCKER_VERSION=$(docker version --format '{{.Server.Version}}' 2>/dev/null || echo none)
FIRST_RUNNER=${RUNNERS%% *}
FIRST_DB=${DBS%% *}

provider_info() { # runner db indent
    "$JAVA_BIN" -cp "$1/target/benchmarks.jar" io.github.rzo1.jpabench.core.ProviderInfo "$2" \
        | sed "2,\$s/^/$3/" | tr -d '\r'
}

# pinned by digest in the root pom; JPABENCH_PG_IMAGE overrides it
PG_IMAGE=${JPABENCH_PG_IMAGE:-$(provider_info "$FIRST_RUNNER" h2 "" | sed -n 's/.*"bench.postgres.image": "\(.*\)".*/\1/p')}

{
    echo "{"
    echo "  \"timestamp\": \"$TIMESTAMP\","
    echo "  \"git\": {\"commit\": \"$GIT_COMMIT\", \"dirty\": $GIT_DIRTY},"
    echo "  \"build\": {\"profiles\": \"$(json_escape "$PROFILES")\", \"built\": $BUILD},"
    echo "  \"java\": {\"version\": \"$(json_escape "$JAVA_VERSION")\", \"vendor\": \"$(json_escape "$JAVA_VENDOR")\"},"
    echo "  \"smoke\": $SMOKE,"
    echo "  \"contributor\": \"$(json_escape "$CONTRIBUTOR")\","
    echo "  \"os\": {\"name\": \"$(uname -s)\", \"release\": \"$(uname -r)\", \"arch\": \"$(uname -m)\", \"pretty\": \"$(json_escape "$OS_PRETTY")\"},"
    echo "  \"cpu\": {\"model\": \"$(json_escape "$CPU")\", \"cores\": \"$CORES\", \"coreTypes\": \"$(json_escape "$CORE_TYPES")\", \"memoryBytes\": $MEMORY_BYTES},"
    echo "  \"machine\": {\"label\": \"$(json_escape "$MACHINE_LABEL")\", \"vendor\": \"$(json_escape "$VENDOR")\", \"model\": \"$(json_escape "$MODEL")\", \"modelId\": \"$(json_escape "$MODEL_ID")\", \"power\": \"$(json_escape "$POWER")\", \"battery\": \"$(json_escape "$BATTERY")\", \"lowPowerMode\": ${LOW_POWER:-null}, \"governor\": \"$(json_escape "$GOVERNOR")\"},"
    echo "  \"docker\": \"$(json_escape "$DOCKER_VERSION")\","
    echo "  \"postgres\": {\"image\": \"$(json_escape "$PG_IMAGE")\", \"cpus\": ${JPABENCH_PG_CPUS:-2}, \"memoryMb\": ${JPABENCH_PG_MEMORY_MB:-2048}, \"settings\": \"fsync=off, autovacuum=off\", \"external\": \"$(json_escape "${JPABENCH_PG_URL:-}")\"},"
    echo "  \"matrix\": {\"runners\": \"$RUNNERS\", \"dbs\": \"$DBS\", \"datasets\": \"$DATASETS\", \"include\": \"$(json_escape "$INCLUDE")\", \"bootstrap\": $BOOTSTRAP},"
    echo "  \"jmh\": {\"modes\": \"ReadBenchmarks thrpt,avgt; WriteBenchmarks ss (fixed batch per iteration)\", \"forks\": $FORKS, \"warmupIterations\": $WARMUP_ITERATIONS, \"iterations\": $ITERATIONS, \"warmupTime\": \"$WARMUP_TIME\", \"time\": \"$TIME\", \"bootstrapWarmup\": $BOOTSTRAP_WARMUP, \"bootstrapIterations\": $BOOTSTRAP_ITERATIONS, \"coldForks\": $COLD_FORKS, \"jvmArgs\": \"$(json_escape "$JVM_ARGS")\"},"
    echo "  \"metering\": {\"mode\": \"$METERING\", \"forks\": $METER_FORKS, \"warmupIterations\": $METER_WARMUP_ITERATIONS, \"iterations\": $METER_ITERATIONS, \"time\": \"$METER_TIME\"},"
    echo "  \"order\": {\"mode\": \"$ORDER\", \"seed\": \"$SEED\", \"log\": \"execution.tsv\"},"
    # resolved settings of the first database, one entry per runner
    echo "  \"runners\": {"
    first=true
    for runner in $RUNNERS; do
        if [ "$first" = true ]; then first=false; else echo ","; fi
        printf '    "%s": ' "$runner"
        provider_info "$runner" "$FIRST_DB" "    "
    done
    echo ""
    echo "  },"
    # resolved settings per database (db.<id>.* keys differ)
    echo "  \"runnersByDb\": {"
    first_db=true
    for db in $DBS; do
        if [ "$first_db" = true ]; then first_db=false; else echo ","; fi
        printf '    "%s": {\n' "$db"
        first=true
        for runner in $RUNNERS; do
            if [ "$first" = true ]; then first=false; else echo ","; fi
            printf '      "%s": ' "$runner"
            provider_info "$runner" "$db" "      "
        done
        echo ""
        printf '    }'
    done
    echo ""
    echo "  }"
    echo "}"
} > "$OUT/metadata.json"
log "results go to $OUT"

# --- result verification -------------------------------------------------------------------------
# Read trials log "# jpa-bench: verify <operation>=<checksum>", data loads "# jpa-bench: dataset <size> seed=..."
VERIFY="$OUT/verification.tsv"
printf 'runner\tdb\tdataset\toperation\tchecksum\n' > "$VERIFY"
DATASETS_TSV="$OUT/datasets.tsv"
printf 'runner\tdb\tdataset\tseed\tchecksum\trecords\tcounts\n' > "$DATASETS_TSV"

collect_verification() { # runner db dataset log
    grep '^# jpa-bench: verify ' "$4" | sed 's/^# jpa-bench: verify //' | sort -u | while IFS='=' read -r op sum; do
        printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$op" "$sum"
    done >> "$VERIFY" || true
    grep '^# jpa-bench: dataset ' "$4" | sed -n \
        's/^# jpa-bench: dataset \([^ ]*\) seed=\([^ ]*\) checksum=\([^ ]*\) records=\([^ ]*\) (\(.*\)) loaded.*/\1\t\2\t\3\t\4\t\5/p' \
        | sort -u | while IFS="$TAB" read -r size seed sum records counts; do
        printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$size" "$seed" "$sum" "$records" "$counts"
    done >> "$DATASETS_TSV" || true
}

# Prints "db dataset operation" for every operation whose checksum differs between runners.
verification_mismatches() {
    sort -u "$VERIFY" | awk -F'\t' '$1 != "runner" {
        key = $2 " " $3 " " $4
        if (!((key, $5) in seen)) { seen[key, $5] = 1; distinct[key]++ }
    } END { for (key in distinct) if (distinct[key] > 1) print key }' | sort
}

# --- jobs ----------------------------------------------------------------------------------------
# A job is "kind runner db dataset": bench (timed), metrics (metered), bootstrap (warm), coldstart.
JMH_COMMON=(-foe true)
if [ -n "$JVM_ARGS" ]; then
    JMH_COMMON+=(-jvmArgs "$JVM_ARGS")
fi
case "$METERING" in
    separate|off) TIMED_METERING=off ;;
    *) TIMED_METERING=on ;;
esac

run_job() { # kind runner db dataset
    local kind=$1 runner=$2 db=$3 dataset=$4
    local jar="$runner/target/benchmarks.jar" name="$runner-$db-$dataset"
    # runtime enhancement: every fork starts with the runner's agent jar
    local agent=()
    if [ -f "$runner/target/javaagent.jar" ]; then
        agent=(-jvmArgsAppend "-javaagent:$PWD/$runner/target/javaagent.jar")
    fi
    case "$kind" in
        bench)
            JPABENCH_METERING=$TIMED_METERING JPABENCH_SQL_DIR="$OUT/sql/$name" \
                "$JAVA_BIN" -jar "$jar" "$INCLUDE" -e emfBootstrap \
                "${JMH_COMMON[@]}" ${agent[@]+"${agent[@]}"} -f "$FORKS" -tu ms \
                -wi "$WARMUP_ITERATIONS" -i "$ITERATIONS" -w "$WARMUP_TIME" -r "$TIME" \
                -p db="$db" -p dataset="$dataset" \
                -rf json -rff "$OUT/$name.json" > "$OUT/logs/$name.log" 2>&1 || return 1
            collect_verification "$runner" "$db" "$dataset" "$OUT/logs/$name.log" ;;
        metrics)
            mkdir -p "$OUT/metrics"
            JPABENCH_METERING=on JPABENCH_SQL_DIR="$OUT/sql/$name" \
                "$JAVA_BIN" -jar "$jar" "$INCLUDE" -e emfBootstrap \
                "${JMH_COMMON[@]}" ${agent[@]+"${agent[@]}"} -f "$METER_FORKS" -tu ms \
                -wi "$METER_WARMUP_ITERATIONS" -i "$METER_ITERATIONS" -w "$METER_TIME" -r "$METER_TIME" \
                -p db="$db" -p dataset="$dataset" \
                -rf json -rff "$OUT/metrics/$name.json" > "$OUT/logs/metrics-$name.log" 2>&1 || return 1
            collect_verification "$runner" "$db" "$dataset" "$OUT/logs/metrics-$name.log" ;;
        bootstrap)
            JPABENCH_METERING=$TIMED_METERING JPABENCH_SQL_DIR="$OUT/sql/$runner-$db-bootstrap" \
                "$JAVA_BIN" -jar "$jar" 'BootstrapBenchmark' \
                "${JMH_COMMON[@]}" ${agent[@]+"${agent[@]}"} -f "$FORKS" -bm ss -tu ms \
                -wi "$BOOTSTRAP_WARMUP" -i "$BOOTSTRAP_ITERATIONS" \
                -p db="$db" \
                -rf json -rff "$OUT/$runner-$db-bootstrap.json" > "$OUT/logs/$runner-$db-bootstrap.log" 2>&1 ;;
        coldstart)
            JPABENCH_METERING=$TIMED_METERING \
                "$JAVA_BIN" -jar "$jar" 'BootstrapBenchmark' \
                "${JMH_COMMON[@]}" ${agent[@]+"${agent[@]}"} -f "$COLD_FORKS" -bm ss -tu ms -wi 0 -i 1 \
                -p db="$db" \
                -rf json -rff "$OUT/$runner-$db-coldstart.json" > "$OUT/logs/$runner-$db-coldstart.log" 2>&1 ;;
    esac
}

# runner by runner (sequential order)
JOBS=()
for runner in $RUNNERS; do
    for db in $DBS; do
        for dataset in $DATASETS; do
            JOBS+=("bench $runner $db $dataset")
            if [ "$METERING" = separate ]; then
                JOBS+=("metrics $runner $db $dataset")
            fi
        done
        if [ "$BOOTSTRAP" = true ]; then
            JOBS+=("bootstrap $runner $db -" "coldstart $runner $db -")
        fi
    done
done

case "$ORDER" in
    interleave)
        # group by kind, database and dataset; runners back to back inside a group
        ORDERED=$(printf '%s\n' "${JOBS[@]}" | awk '{
            k = ($1 == "bench" ? 1 : $1 == "metrics" ? 2 : $1 == "bootstrap" ? 3 : 4)
            printf "%d\t%s\t%s\t%06d\t%s\n", k, $3, $4, NR, $0 }' \
            | sort -t"$TAB" -k1,1n -k2,2 -k3,3 -k4,4n | cut -f5) ;;
    shuffle)
        ORDERED=$(printf '%s\n' "${JOBS[@]}" | awk -v seed="$SEED" 'BEGIN { srand(seed) }
            { printf "%.12f\t%s\n", rand(), $0 }' | sort -t"$TAB" -k1,1n | cut -f2) ;;
    *)
        ORDERED=$(printf '%s\n' "${JOBS[@]}") ;;
esac
JOBS=()
while IFS= read -r job; do
    JOBS+=("$job")
done <<< "$ORDERED"

EXECUTION="$OUT/execution.tsv"
printf 'index\tkind\trunner\tdb\tdataset\tstart\tend\tstatus\n' > "$EXECUTION"
FAILED=0
index=0
for job in "${JOBS[@]}"; do
    index=$((index + 1))
    read -r kind runner db dataset <<< "$job"
    log "[$index/${#JOBS[@]}] $kind $runner $db $dataset"
    start=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    status=ok
    if ! run_job "$kind" "$runner" "$db" "$dataset"; then
        status=failed
        FAILED=$((FAILED + 1))
        log "FAILED: $kind $runner $db $dataset (see $OUT/logs)"
    fi
    printf '%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$index" "$kind" "$runner" "$db" "$dataset" "$start" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$status" >> "$EXECUTION"
done

MISMATCHES=$(verification_mismatches)
if [ -n "$MISMATCHES" ]; then
    log "WARNING: providers return different results (db dataset operation), see $VERIFY:"
    echo "$MISMATCHES" | sed 's/^/    /'
    echo "$MISMATCHES" > "$OUT/verification-mismatches.txt"
else
    log "verification: all runners return the same results"
fi

log "done: $OUT"
if [ "$FAILED" -gt 0 ]; then
    log "$FAILED job(s) failed"
    exit 1
fi
