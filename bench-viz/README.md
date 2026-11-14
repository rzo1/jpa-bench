# bench-viz

Charts, HTML reports and the results site for `run.sh` results. Python 3.12+, [uv](https://docs.astral.sh/uv/),
pandas, matplotlib, Jinja2; ruff and pytest.

## Commands

```bash
cd bench-viz
uv run jpa-bench-viz ../results/<timestamp>                 # report in ../results/<timestamp>/report/
uv run jpa-bench-viz ../results/<timestamp> -o /tmp/r --format png
uv run jpa-bench-viz add-run ../results/<timestamp> <runs-dir> [--contributor HANDLE] [--machine LABEL]
uv run jpa-bench-viz validate <run-dir or runs-dir> [--submission]
uv run jpa-bench-viz site <runs-dir> -o <site-dir>
```

| Command | Does |
|---|---|
| `report` (default) | one self-contained `index.html` (charts inlined), `charts/`, `scores.csv`, `statements.csv` |
| `add-run` | copies a run to `<runs-dir>/<yyyy-mm-dd>-<machine>-<contributor>/`, gzips `logs/`, skips `report/` |
| `validate` | exit 1 on errors: metadata and required fields, empty or broken JSON, failed or missing jobs, verification mismatches, dataset checksum not the reference. `--submission` also checks the directory name, gzipped logs, no `report/`, size below 50 MB |
| `site` | `index.html` (all runs, smoke and reduced runs separate), `datasets.html`, `runs/<run-id>/index.html` |

The site has no external resources: CSS and charts are inline, links are relative. It works from `file://`.

## Report

Top of every report: run date and duration, contributor, machine, OS, power source, JDK, databases, jpa-bench
commit, JMH settings, datasets with their checksum, verification result, providers with version, enhancement,
OpenJPA snapshot build and revision. Smoke runs (`run.sh --smoke`, or 1 fork and 1 iteration) and reduced runs
(fewer than 2 forks or 5 iterations) are flagged as not comparable.

Then per database and dataset (and per database for `emfBootstrap` warm and cold start):

- relative overview per JMH mode, normalized to the fastest provider (1.0); throughput `score / best`, time
  modes `best / score`;
- score tables (absolute score ± error, factor to the fastest);
- statements per operation by kind, JDBC round trips, SQL characters, longest SELECT, joined tables;
- per benchmark the scores and the distinct SQL of the first iteration per provider.

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
- Runner colours follow `KNOWN_RUNNERS` in `results.py`, regardless of rank.

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
