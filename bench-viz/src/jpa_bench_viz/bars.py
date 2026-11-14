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
"""Data of the HTML/CSS charts: heatmap cells, bars and stacked statement bars."""

from __future__ import annotations

import math
import re
from typing import Any

import pandas as pd

from jpa_bench_viz.results import (
    HIGHER_IS_BETTER,
    KNOWN_RUNNERS,
    MODE_LABELS,
    STATEMENT_KINDS,
    STATEMENT_LABELS,
)

# runner -> (colour slot 1-8, striped); the -agent runners reuse the slot of their build-time runner
RUNNER_SLOTS: dict[str, tuple[int, bool]] = {
    "runner-openjpa-4.1": (1, False),
    "runner-openjpa-4.2": (2, False),
    "runner-openjpa-4.1-agent": (1, True),
    "runner-openjpa-4.2-agent": (2, True),
    "runner-hibernate-6.6": (3, False),
    "runner-hibernate-7": (4, False),
    "runner-eclipselink-4": (5, False),
    "runner-eclipselink-5": (6, False),
    "runner-eclipselink-4-woven": (7, False),
    "runner-eclipselink-5-woven": (8, False),
}
assert set(RUNNER_SLOTS) == set(KNOWN_RUNNERS)

# heatmap levels by factor to the fastest, darkest first
HEAT_LEVELS: tuple[float, ...] = (0.95, 0.8, 0.6, 0.4, 0.2)


def slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def runner_class(runner: str) -> str:
    return f"runner-{slug(runner.removeprefix('runner-'))}" if runner in RUNNER_SLOTS else "runner-other"


def heat_level(factor: float) -> int:
    """5 for the fastest (factor >= 0.95) down to 0 (factor < 0.2); -1 for no value."""
    if factor is None or math.isnan(factor):
        return -1
    for i, threshold in enumerate(HEAT_LEVELS):
        if factor >= threshold:
            return len(HEAT_LEVELS) - i
    return 0


def sql_anchor(section: str, benchmark: str, runner: str) -> str:
    return f"sql-{section}-{slug(benchmark)}-{slug(runner.removeprefix('runner-'))}"


def _pct(value: float, top: float) -> float:
    if not top or math.isnan(value):
        return 0.0
    return max(0.0, min(100.0, 100.0 * value / top))


def score_bars(
    frame: pd.DataFrame, runners: list[str], labels: dict[str, str], section: str
) -> dict[str, Any]:
    """One bar per runner for one benchmark and mode; width relative to the largest ``score + error``."""
    mode = str(frame["mode"].iloc[0])
    unit = str(frame["unit"].iloc[0])
    by_runner = frame.set_index("runner")
    top = max(
        (s + e for s, e in zip(frame["score"], frame["error"], strict=True) if not math.isnan(s)), default=0
    )
    rows = []
    for runner in runners:
        if runner not in by_runner.index:
            continue
        row = by_runner.loc[runner]
        score, error = float(row["score"]), float(row["error"])
        benchmark = str(row["benchmark"])
        rows.append(
            {
                "runner": runner,
                "label": labels.get(runner, runner),
                "css": runner_class(runner),
                "width": _pct(score, top),
                "err_left": _pct(score - error, top),
                "err_width": _pct(score + error, top) - _pct(score - error, top),
                "score": score,
                "error": error,
                "factor": float(row["relative"]),
                "best": bool(row["best"]),
                "tie": bool(row["tie"]) and not bool(row["best"]),
                "sql": sql_anchor(section, benchmark, runner),
            }
        )
    return {
        "mode": mode,
        "title": MODE_LABELS.get(mode, mode),
        "unit": unit,
        "better": "higher is better" if mode in HIGHER_IS_BETTER else "lower is better",
        "rows": rows,
    }


def statement_bars(table: pd.DataFrame, runners: list[str], labels: dict[str, str]) -> list[dict[str, Any]]:
    """Per benchmark: statements per operation stacked by kind, one bar per runner, common scale."""
    charts = []
    for benchmark in sorted(table["benchmark"].unique()):
        part = table[table["benchmark"] == benchmark].set_index("runner")
        kinds = [k for k in STATEMENT_KINDS if k in part.columns]
        totals = {r: sum(_value(part, r, k) for k in kinds) for r in runners if r in part.index}
        top = max(totals.values(), default=0)
        rows = []
        for runner, total in totals.items():
            rows.append(
                {
                    "runner": runner,
                    "label": labels.get(runner, runner),
                    "css": runner_class(runner),
                    "total": total,
                    "segments": [
                        {
                            "kind": STATEMENT_LABELS[k],
                            "css": f"kind-{slug(STATEMENT_LABELS[k])}",
                            "value": _value(part, runner, k),
                            "width": _pct(_value(part, runner, k), top),
                        }
                        for k in kinds
                        if _value(part, runner, k) > 0
                    ],
                    "executions": _value(part, runner, "jdbcExecutions"),
                    "batches": _value(part, runner, "jdbcBatches"),
                    "chars": _value(part, runner, "sqlChars"),
                }
            )
        charts.append({"benchmark": benchmark, "rows": rows})
    return charts


def _value(frame: pd.DataFrame, runner: str, column: str) -> float:
    if column not in frame.columns or runner not in frame.index:
        return 0.0
    value = float(frame.at[runner, column])
    return 0.0 if math.isnan(value) else value
