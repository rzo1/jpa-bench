# bench-viz

HTML reports, the results site and run validation for `run.sh` results. Python 3.12+,
[uv](https://docs.astral.sh/uv/), pandas, Jinja2; ruff and pytest. Charts are plain HTML and CSS, no JavaScript.

## Commands

```bash
cd bench-viz
uv run jpa-bench-viz ../results/<timestamp>                 # report in ../results/<timestamp>/report/
uv run jpa-bench-viz ../results/<timestamp> -o /tmp/r
uv run jpa-bench-viz add-run ../results/<timestamp> <runs-dir> [--contributor HANDLE] [--machine LABEL]
uv run jpa-bench-viz validate <run-dir or runs-dir> [--submission]
uv run jpa-bench-viz site <runs-dir> -o <site-dir>
```

| Command | Does |
|---|---|
| `report` (default) | one self-contained `index.html`, `scores.csv`, `statements.csv` |
| `add-run` | copies a run to `<runs-dir>/<yyyy-mm-dd>-<machine>-<contributor>/`, gzips `logs/`, skips `report/` |
| `validate` | exit 1 on errors: metadata and required fields, empty or broken JSON, failed or missing jobs, verification mismatches, dataset checksum not the reference. `--submission` also checks the directory name, gzipped logs, no `report/`, size below 50 MB |
| `site` | `index.html` (all runs, smoke and reduced runs separate), `datasets.html`, `runs/<run-id>/index.html` |

The site has no external resources: CSS is inline, links are relative. It works from `file://`.

## Report

Top of every report: run date and duration, contributor, machine, OS, power source, JDK, databases, jpa-bench
commit, JMH settings, datasets with their checksum, verification result, providers with version, enhancement,
OpenJPA snapshot build and revision. Smoke runs (`run.sh --smoke`, or 1 fork and 1 iteration) and reduced runs
(fewer than 2 forks or 5 iterations) are flagged as not comparable.

Then per database and dataset (and per database for `emfBootstrap` warm and cold start):

- heatmap per JMH mode, benchmark x provider: factor to the fastest (1.0); throughput `score / best`, time
  modes `best / score`. Fastest: bold frame; tie (`≈`, thin frame): `score ± error` overlaps the fastest's
  interval. One benchmark (`emfBootstrap`): bars instead;
- score tables (score ± error, factor; same best and tie marks);
- statements per operation: stacked bar by kind per provider, JDBC executions, batches, SQL characters;
- per benchmark: one bar per provider and mode (width relative to the largest `score + error`, error line,
  factor, exact value as tooltip), linked to the distinct SQL of the first iteration per provider.

Each runner has a fixed colour (`--series-N` CSS variables in `KNOWN_RUNNERS` order, light and dark variant);
variants (`-agent`, `-enhanced`, `-woven`) use the colour of their base runner, striped.

Labels name the enhancement mode from `bench.provider.enhancement`, e.g. `EclipseLink 5.0.2 (not woven)`.

How the numbers are read:

- Files match `<runner>-<db>-<dataset>.json`; `db` and `dataset` come from the JMH params when present. Empty
  files (aborted or running) are skipped.
- `metering` `separate` or `off`: statement metrics from `metrics/*.json`, the timed runs' counters are ignored;
  `inline` or missing: from the timed runs.
- Single shot results with a batch (write benchmarks) are divided by `measurementBatchSize`.
- Per-operation metric = mean of the secondary metric's `rawData` (JMH sums `EVENTS` counters in `score`).
- Statement metrics come from the throughput run, else the average time run.
- `scoreError` `NaN` (one iteration) is shown as 0.

## Datasets

`src/jpa_bench_viz/datasets.json` describes the data model and the row counts and checksums of S, M and L.
`tests/test_site.py` checks it against `DatasetSize.java`, `DataGenerator.java`, the DDL and the golden
checksum in `DatasetLoaderTest.java`. The `sizes` section comes from the generator:

```bash
./mvnw -B -q compile -pl bench-data
jshell --class-path bench-data/target/classes bench-viz/scripts/datasets.jsh
```

## Development

```bash
uv sync
uv run ruff check
uv run ruff format --check
uv run pytest
```

`tests/fixtures/run/` is a trimmed `run.sh --smoke` result (H2, dataset S, two runners, two benchmarks,
bootstrap results, one empty result file).
