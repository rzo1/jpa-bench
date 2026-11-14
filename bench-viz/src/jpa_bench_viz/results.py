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
"""Reads a ``results/<timestamp>/`` directory written by ``run.sh`` into tidy pandas frames."""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pandas as pd

# Runner modules in display order; the colour of a runner follows this order (never its rank).
KNOWN_RUNNERS: tuple[str, ...] = (
    "runner-openjpa-4.1",
    "runner-openjpa-4.2",
    "runner-hibernate-6.6",
    "runner-hibernate-7",
    "runner-eclipselink-4",
    "runner-eclipselink-5",
)

KNOWN_DBS: tuple[str, ...] = ("h2", "postgresql")
DB_LABELS: dict[str, str] = {"h2": "H2 (in-memory)", "postgresql": "PostgreSQL"}
DATASET_ORDER: tuple[str, ...] = ("S", "M", "L", "bootstrap")

MODE_LABELS: dict[str, str] = {
    "thrpt": "Throughput",
    "avgt": "Average time",
    "ss": "Single shot time",
    "sample": "Sampling time",
}
# Modes where a higher score is better; every other JMH mode measures time.
HIGHER_IS_BETTER: frozenset[str] = frozenset({"thrpt"})

STATEMENT_KINDS: tuple[str, ...] = ("stmtSelect", "stmtInsert", "stmtUpdate", "stmtDelete", "stmtOther")
STATEMENT_LABELS: dict[str, str] = {
    "stmtSelect": "SELECT",
    "stmtInsert": "INSERT",
    "stmtUpdate": "UPDATE",
    "stmtDelete": "DELETE",
    "stmtOther": "other",
}
METRIC_LABELS: dict[str, str] = {
    **STATEMENT_LABELS,
    "stmtTotal": "statements",
    "jdbcExecutions": "JDBC round trips",
    "jdbcBatches": "JDBC batches",
    "jdbcBatchedRows": "batched rows",
    "sqlChars": "SQL chars",
    "selectJoins": "JOINs in SELECTs",
    "selectMaxChars": "longest SELECT (chars)",
    "ops": "ops per iteration",
}

# <runner>-<db>-<dataset>.json; the runner name itself contains dashes.
_FILE_PATTERN = re.compile(r"^(?P<runner>.+)-(?P<db>[^-]+)-(?P<dataset>[^-]+)\.json$")


@dataclass(frozen=True)
class Results:
    """All results of one run directory."""

    directory: Path
    metadata: dict[str, Any]
    scores: pd.DataFrame
    metrics: pd.DataFrame
    runners: list[str] = field(default_factory=list)

    def provider_label(self, runner: str) -> str:
        return provider_label(self.metadata, runner)

    def sql_dir(self, runner: str, db: str, dataset: str) -> Path:
        return self.directory / "sql" / f"{runner}-{db}-{dataset}"


def provider_label(metadata: dict[str, Any], runner: str) -> str:
    """Human readable provider name and version of a runner, e.g. ``Hibernate ORM 7.4.15.Final``."""
    harness = metadata.get("runners", {}).get(runner, {}).get("harness", {})
    name = harness.get("bench.provider.name")
    version = harness.get("bench.provider.version")
    if name and version:
        return f"{name} {version}"
    return runner.removeprefix("runner-")


def scope_label(db: str, dataset: str) -> str:
    """``H2 (in-memory), dataset S`` or ``PostgreSQL, EMF bootstrap``."""
    what = "EMF bootstrap" if dataset == "bootstrap" else f"dataset {dataset}"
    return f"{DB_LABELS.get(db, db)}, {what}"


def runner_order(runners: set[str] | list[str]) -> list[str]:
    known = [r for r in KNOWN_RUNNERS if r in runners]
    return known + sorted(r for r in runners if r not in KNOWN_RUNNERS)


def ordered(values: set[str] | list[str], order: tuple[str, ...]) -> list[str]:
    known = [v for v in order if v in values]
    return known + sorted(v for v in values if v not in order)


def _flatten(raw: Any) -> list[float]:
    """JMH ``rawData`` is a list (forks) of lists (iterations)."""
    values: list[float] = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, list):
                values.extend(_flatten(item))
            elif isinstance(item, int | float):
                values.append(float(item))
    return values


def _number(value: Any) -> float:
    """Converts a JMH number; ``"NaN"`` (one iteration, no error estimate) becomes NaN."""
    try:
        return float(value)
    except (TypeError, ValueError):
        return math.nan


def _short_name(benchmark: str) -> tuple[str, str]:
    """``io...bench.ReadBenchmarks.findById`` -> (``ReadBenchmarks``, ``findById``)."""
    parts = benchmark.split(".")
    if len(parts) >= 2:
        return parts[-2], parts[-1]
    return "", benchmark


def load_metadata(directory: Path) -> dict[str, Any]:
    path = directory / "metadata.json"
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def load(directory: Path | str) -> Results:
    """Reads every JMH result file and ``metadata.json`` of a run directory."""
    directory = Path(directory)
    if not directory.is_dir():
        raise FileNotFoundError(f"not a directory: {directory}")
    metadata = load_metadata(directory)
    score_rows: list[dict[str, Any]] = []
    metric_rows: list[dict[str, Any]] = []
    for path in sorted(directory.glob("*.json")):
        if path.name == "metadata.json":
            continue
        match = _FILE_PATTERN.match(path.name)
        if match is None:
            continue
        text = path.read_text(encoding="utf-8").strip()
        if not text:
            # run.sh creates the file only when JMH finishes; an empty file is an aborted or running run
            continue
        try:
            entries = json.loads(text)
        except json.JSONDecodeError:
            continue
        if not isinstance(entries, list):
            continue
        runner = match["runner"]
        for entry in entries:
            _read_entry(entry, runner, match["db"], match["dataset"], path.name, score_rows, metric_rows)

    scores = pd.DataFrame(score_rows, columns=_SCORE_COLUMNS)
    metrics = pd.DataFrame(metric_rows, columns=_METRIC_COLUMNS)
    _label_benchmarks(scores, metrics)
    runners = runner_order(set(scores["runner"]))
    if not scores.empty:
        scores = _add_relative(scores)
    return Results(directory=directory, metadata=metadata, scores=scores, metrics=metrics, runners=runners)


_SCORE_COLUMNS = [
    "file",
    "runner",
    "db",
    "dataset",
    "group",
    "method",
    "extra",
    "benchmark",
    "mode",
    "unit",
    "score",
    "error",
    "samples",
    "forks",
    "sql_file",
]
_METRIC_COLUMNS = [
    "runner",
    "db",
    "dataset",
    "group",
    "method",
    "extra",
    "benchmark",
    "mode",
    "metric",
    "per_op",
    "samples",
]


def _read_entry(
    entry: dict[str, Any],
    runner: str,
    file_db: str,
    file_dataset: str,
    file_name: str,
    score_rows: list[dict[str, Any]],
    metric_rows: list[dict[str, Any]],
) -> None:
    params: dict[str, str] = {str(k): str(v) for k, v in (entry.get("params") or {}).items()}
    all_params = dict(sorted(params.items()))
    db = params.pop("db", file_db)
    dataset = params.pop("dataset", file_dataset)
    extra = ", ".join(f"{k}={v}" for k, v in sorted(params.items()))
    group, method = _short_name(str(entry.get("benchmark", "")))
    mode = str(entry.get("mode", ""))
    primary = entry.get("primaryMetric") or {}
    raw = _flatten(primary.get("rawData"))
    key = {
        "runner": runner,
        "db": db,
        "dataset": dataset,
        "group": group,
        "method": method,
        "extra": extra,
        "benchmark": method,
        "mode": mode,
    }
    score_rows.append(
        {
            **key,
            "file": file_name,
            "unit": str(primary.get("scoreUnit", "")),
            "score": _number(primary.get("score")),
            "error": _number(primary.get("scoreError")),
            "samples": len(raw),
            "forks": int(entry.get("forks", 0) or 0),
            "sql_file": sql_file_name(group, method, mode, all_params),
        }
    )
    for name, secondary in (entry.get("secondaryMetrics") or {}).items():
        values = _flatten((secondary or {}).get("rawData"))
        # JMH sums EVENTS counters over iterations and forks in "score"; the counters already are per
        # operation per iteration, so the per-operation value is the mean of the raw data.
        per_op = sum(values) / len(values) if values else _number((secondary or {}).get("score"))
        metric_rows.append({**key, "metric": str(name), "per_op": per_op, "samples": len(values)})


def sql_file_name(group: str, method: str, mode: str, params: dict[str, str]) -> str:
    """Name of the SQL sidecar file, as written by ``StatementCounters`` in bench-core."""
    name = f"{group}.{method}-{mode}" + "".join(f"-{k}={v}" for k, v in sorted(params.items()))
    return re.sub(r"[^A-Za-z0-9=._-]", "_", name) + ".sql"


def _label_benchmarks(scores: pd.DataFrame, metrics: pd.DataFrame) -> None:
    """Appends the extra parameters to the benchmark name only where a method runs with several values."""
    if scores.empty:
        return
    variants = scores.groupby("method")["extra"].nunique()
    ambiguous = set(variants[variants > 1].index)
    for frame in (scores, metrics):
        if frame.empty:
            continue
        frame["benchmark"] = [
            f"{method} [{extra}]" if method in ambiguous and extra else method
            for method, extra in zip(frame["method"], frame["extra"], strict=True)
        ]


def _add_relative(scores: pd.DataFrame) -> pd.DataFrame:
    """Adds ``relative`` (1.0 = fastest provider, smaller = slower) and its error per comparison group."""
    scores = scores.copy()
    scores["error"] = scores["error"].fillna(0.0)
    keys = ["db", "dataset", "benchmark", "mode", "unit"]
    relative: list[float] = []
    relative_error: list[float] = []
    grouped = scores.groupby(keys)["score"]
    best = grouped.transform("max").where(scores["mode"].isin(HIGHER_IS_BETTER), grouped.transform("min"))
    for score, error, top, mode in zip(scores["score"], scores["error"], best, scores["mode"], strict=True):
        if not score or math.isnan(score) or not top or math.isnan(top):
            relative.append(math.nan)
            relative_error.append(math.nan)
            continue
        if mode in HIGHER_IS_BETTER:
            rel = score / top
            rel_err = error / top
        else:
            rel = top / score
            # first-order propagation of the error of the score through top / score
            rel_err = top * error / (score * score)
        relative.append(rel)
        relative_error.append(rel_err)
    scores["relative"] = relative
    scores["relative_error"] = relative_error
    return scores


def statement_table(metrics: pd.DataFrame) -> pd.DataFrame:
    """Per-operation metrics, one row per (runner, db, dataset, benchmark), one column per metric.

    Statement counts do not depend on the JMH mode; throughput results are preferred, average time is used
    where no throughput result exists.
    """
    if metrics.empty:
        return pd.DataFrame()
    preference = {"thrpt": 0, "avgt": 1}
    frame = metrics.assign(_pref=metrics["mode"].map(lambda m: preference.get(m, 2)))
    keys = ["runner", "db", "dataset", "benchmark"]
    best_mode = frame.groupby(keys)["_pref"].transform("min")
    frame = frame[frame["_pref"] == best_mode]
    table = frame.pivot_table(index=keys, columns="metric", values="per_op", aggfunc="mean")
    table.columns.name = None
    return table.reset_index()
