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
    "runner-openjpa-4.1-agent",
    "runner-openjpa-4.2-agent",
    "runner-hibernate-6.6",
    "runner-hibernate-7",
    "runner-eclipselink-4",
    "runner-eclipselink-5",
    "runner-eclipselink-4-woven",
    "runner-eclipselink-5-woven",
)

KNOWN_DBS: tuple[str, ...] = ("h2", "postgresql")
DB_LABELS: dict[str, str] = {"h2": "H2 (in-memory)", "postgresql": "PostgreSQL"}
DATASET_ORDER: tuple[str, ...] = ("S", "M", "L", "bootstrap", "coldstart")
# "datasets" of the emfBootstrap files
BOOTSTRAP_LABELS: dict[str, str] = {
    "bootstrap": "EMF bootstrap (warm)",
    "coldstart": "EMF bootstrap (cold start)",
}

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
    "selectJoins": "joined tables in SELECTs",
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
    verification: pd.DataFrame = field(default_factory=pd.DataFrame)

    def provider_label(self, runner: str) -> str:
        return provider_label(self.metadata, runner)

    def sql_dir(self, runner: str, db: str, dataset: str) -> Path:
        return self.directory / "sql" / f"{runner}-{db}-{dataset}"


def provider_label(metadata: dict[str, Any], runner: str) -> str:
    """Provider, version and enhancement of a runner, e.g. ``EclipseLink 5.0.2 (not woven)``."""
    harness = metadata.get("runners", {}).get(runner, {}).get("harness", {})
    name = harness.get("bench.provider.name")
    version = harness.get("bench.provider.version")
    base = f"{name} {version}" if name and version else runner.removeprefix("runner-")
    enhancement = enhancement_label(harness.get("bench.provider.enhancement"), name or runner)
    return f"{base} ({enhancement})" if enhancement else base


def enhancement_label(enhancement: str | None, provider: str) -> str:
    """Short form of ``bench.provider.enhancement``, e.g. ``runtime enhanced`` or ``not woven``."""
    if not enhancement:
        return ""
    weaving = "eclipselink" in provider.lower()
    if enhancement.strip().lower() in ("none", "false", "off"):
        return "not woven" if weaving else "not enhanced"
    if weaving or "weav" in enhancement.lower():
        return "woven"
    if "runtime" in enhancement.lower() or "agent" in enhancement.lower():
        return "runtime enhanced"
    return "enhanced"


def scope_label(db: str, dataset: str) -> str:
    """``H2 (in-memory), dataset S`` or ``PostgreSQL, EMF bootstrap (warm)``."""
    what = BOOTSTRAP_LABELS.get(dataset, f"dataset {dataset}")
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


VERIFICATION_COLUMNS = ["runner", "db", "dataset", "operation", "checksum"]


def load_verification(directory: Path) -> pd.DataFrame:
    """Reads ``verification.tsv`` (result checksums of the read operations per runner), if present."""
    path = directory / "verification.tsv"
    if not path.is_file():
        return pd.DataFrame(columns=VERIFICATION_COLUMNS)
    frame = pd.read_csv(path, sep="\t", dtype=str)
    missing = [c for c in VERIFICATION_COLUMNS if c not in frame.columns]
    if missing:
        return pd.DataFrame(columns=VERIFICATION_COLUMNS)
    return frame[VERIFICATION_COLUMNS].drop_duplicates()


def verification_mismatches(verification: pd.DataFrame) -> set[tuple[str, str, str]]:
    """The (db, dataset, operation) keys whose checksum is not the same for every runner."""
    if verification.empty:
        return set()
    distinct = verification.groupby(["db", "dataset", "operation"])["checksum"].nunique()
    return {(str(db), str(ds), str(op)) for (db, ds, op), n in distinct.items() if n > 1}


def load(directory: Path | str) -> Results:
    """Reads every JMH result file and ``metadata.json`` of a run directory."""
    directory = Path(directory)
    if not directory.is_dir():
        raise FileNotFoundError(f"not a directory: {directory}")
    metadata = load_metadata(directory)
    score_rows: list[dict[str, Any]] = []
    metric_rows: list[dict[str, Any]] = []
    # metering separate/off: metrics from metrics/*.json only; inline (or unset): from the timed runs
    timed_metered = metadata.get("metering", {}).get("mode", "inline") == "inline"
    for path in sorted(directory.glob("*.json")):
        if path.name == "metadata.json":
            continue
        _read_file(path, score_rows, metric_rows if timed_metered else [])
    for path in sorted((directory / "metrics").glob("*.json")):
        _read_file(path, [], metric_rows)

    scores = pd.DataFrame(score_rows, columns=_SCORE_COLUMNS)
    metrics = pd.DataFrame(metric_rows, columns=_METRIC_COLUMNS)
    _label_benchmarks(scores, metrics)
    runners = runner_order(set(scores["runner"]))
    if not scores.empty:
        scores = _add_relative(scores)
    return Results(
        directory=directory,
        metadata=metadata,
        scores=scores,
        metrics=metrics,
        runners=runners,
        verification=load_verification(directory),
    )


def _read_file(path: Path, score_rows: list[dict[str, Any]], metric_rows: list[dict[str, Any]]) -> None:
    match = _FILE_PATTERN.match(path.name)
    if match is None:
        return
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        # aborted or still running
        return
    try:
        entries = json.loads(text)
    except json.JSONDecodeError:
        return
    if not isinstance(entries, list):
        return
    for entry in entries:
        _read_entry(entry, match["runner"], match["db"], match["dataset"], path.name, score_rows, metric_rows)


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
    # JMH reports a single shot with a batch (write benchmarks) as the time of the whole batch
    batch = _batch_size(entry) if mode == "ss" else 1
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
            "score": _number(primary.get("score")) / batch,
            "error": _number(primary.get("scoreError")) / batch,
            "samples": len(raw),
            "forks": int(entry.get("forks", 0) or 0),
            "sql_file": sql_file_name(group, method, mode, all_params),
        }
    )
    for name, secondary in (entry.get("secondaryMetrics") or {}).items():
        values = _flatten((secondary or {}).get("rawData"))
        # JMH sums EVENTS counters in "score"; the per-operation value is the mean of the raw data
        per_op = sum(values) / len(values) if values else _number((secondary or {}).get("score"))
        metric_rows.append({**key, "metric": str(name), "per_op": per_op, "samples": len(values)})


def _batch_size(entry: dict[str, Any]) -> int:
    try:
        return max(1, int(entry.get("measurementBatchSize") or 1))
    except (TypeError, ValueError):
        return 1


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
    scores["best"] = scores["relative"] == 1.0
    scores["tie"] = False
    for _, group in scores.groupby(keys):
        flags = ties(list(group["score"]), list(group["error"]), group["mode"].iloc[0] in HIGHER_IS_BETTER)
        scores.loc[group.index, "tie"] = flags
    return scores


def ties(scores: list[float], errors: list[float], higher_is_better: bool) -> list[bool]:
    """True where ``score ± error`` overlaps the interval of the best score (the best itself included)."""
    valid = [i for i, s in enumerate(scores) if s and not math.isnan(s)]
    if not valid:
        return [False] * len(scores)
    pick = max if higher_is_better else min
    best = pick(valid, key=lambda i: scores[i])

    def err(i: int) -> float:
        return 0.0 if math.isnan(errors[i]) else abs(errors[i])

    low, high = scores[best] - err(best), scores[best] + err(best)
    return [
        i in valid and scores[i] - err(i) <= high and scores[i] + err(i) >= low for i in range(len(scores))
    ]


def statement_table(metrics: pd.DataFrame) -> pd.DataFrame:
    """Per-operation metrics, one row per (runner, db, dataset, benchmark); from thrpt, else avgt."""
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
