# bench-viz

Turns a `results/<timestamp>/` directory written by `run.sh` into comparison charts and a static HTML report.

Not implemented yet. Planned:

- Python 3.12+, managed with [uv](https://docs.astral.sh/uv/) (`pyproject.toml`, `uv.lock`), ruff for lint and
  format, pandas + matplotlib, pytest with a fixture JMH JSON.
- CLI `jpa-bench-viz <results-dir>`: reads every `*.json` JMH result and `metadata.json`, and writes per database
  and per benchmark charts (throughput / average time with error bars, normalized to the fastest provider),
  statement-metric charts (statements per operation by kind, SQL length, joins) and one HTML report with tables.
- Per-operation statement metrics are the mean of the secondary metric's `rawData` (JMH sums EVENTS counters
  over iterations and forks in `score`).
