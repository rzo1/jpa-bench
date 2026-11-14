# bench-viz

Turns a `results/<timestamp>/` directory written by `run.sh` into comparison charts and one static HTML report.

Python 3.12+, managed with [uv](https://docs.astral.sh/uv/) (`pyproject.toml`, `uv.lock`); pandas, matplotlib and
Jinja2; ruff for lint and format; pytest.

## Usage

```bash
cd bench-viz
uv run jpa-bench-viz ../results/<timestamp>                 # writes ../results/<timestamp>/report/
uv run jpa-bench-viz ../results/<timestamp> --out /tmp/r    # other output directory
uv run jpa-bench-viz ../results/<timestamp> --format png    # PNG instead of SVG charts
```

Output:

| File | Content |
|---|---|
| `index.html` | the report, a single self-contained file (SVG inlined, PNG as data URI) |
| `charts/*.svg` / `*.png` | every chart as a separate file |
| `scores.csv` | one row per runner, database, dataset, benchmark and mode: score, error, relative score |
| `statements.csv` | statement metrics per operation, one row per runner, database, dataset and benchmark |

## What the report shows

Per database and dataset (and per database for the two `emfBootstrap` runs, warm and cold start):

- **Relative overview** per JMH mode: every benchmark, one bar per provider, normalized to the fastest
  provider (1.0). For throughput that is `score / best`; for time modes `best / score`, so a longer bar is
  always better. Error bars are the JMH score error scaled the same way.
- **Score tables** per mode: absolute score ± error and the factor relative to the fastest (highlighted).
- **Statements per operation**: one panel per benchmark, statements stacked by kind (SELECT, INSERT,
  UPDATE, DELETE, other).
- **SQL shape**: JDBC round trips and SQL characters per operation (log scale), longest SELECT and joined tables
  in SELECTs per operation (JOIN keywords plus theta joins); plus the full statement metric table.
- **Per benchmark** (collapsed): absolute score with error bars per mode, and the distinct SQL texts of the
  first iteration per provider (from `results/<timestamp>/sql/`), to compare the generated SQL side by side.

The environment (`metadata.json`: commit, build profiles, JDK, OS, CPU, database image, JMH, metering and order
settings) and every provider's settings are listed at the top, followed by the **result verification**: the
checksum every provider returned for each read operation (`verification.tsv`), with operations whose results
differ between providers flagged, because their scores are not comparable.

Every provider label names the enhancement mode from `bench.provider.enhancement`, e.g.
`EclipseLink 5.0.2 (not woven)`, `EclipseLink 5.0.2 (woven)`, `Hibernate ORM 7.4.15.Final (not enhanced)`,
`Apache OpenJPA 4.1.1 (enhanced)`, because it changes what a provider does.

How the numbers are read:

- Results files are matched as `<runner>-<db>-<dataset>.json`; `db` and `dataset` come from the JMH params when
  present. Empty files (an aborted or still running JMH run) are skipped.
- Statement metrics: with `run.sh --metering separate` (the default) or `off` in `metadata.json` the timed runs
  were unmetered, so their counters are ignored and the metrics come from `metrics/<runner>-<db>-<dataset>.json`;
  with `inline` (or no `metering` entry) from the timed runs.
- Single shot results with a batch (the write benchmarks, `measurementBatchSize` 200) are the time of the whole
  batch in JMH; they are divided by the batch size, so every score is per operation.
- JMH sums `@AuxCounters(EVENTS)` counters over iterations and forks in `score`. The counters already are per
  operation per iteration, so the per-operation value is the **mean of the secondary metric's `rawData`**.
- Statement metrics do not depend on the JMH mode; the throughput run is used, the average time run where
  there is none.
- A JMH `scoreError` of `NaN` (a single measurement iteration) is shown as 0: smoke runs have no error
  estimate, and the report says so.
- Each runner keeps its colour in every chart (fixed order: OpenJPA 4.1, OpenJPA 4.2, Hibernate 6.6,
  Hibernate 7, EclipseLink 4, EclipseLink 5, EclipseLink 4 woven, EclipseLink 5 woven), regardless of which
  runners a run contains.

## Development

```bash
uv sync
uv run ruff check
uv run ruff format --check
uv run pytest
```

The tests use `tests/fixtures/run/`, a trimmed copy of a real `run.sh --smoke` result directory (H2, dataset
S, two runners, two benchmarks, plus the bootstrap results and an empty result file).
