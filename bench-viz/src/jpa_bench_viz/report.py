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
"""Static HTML report of one results directory; charts are plain HTML and CSS."""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pandas as pd
from jinja2 import Environment, PackageLoader, select_autoescape

from jpa_bench_viz.bars import (
    RUNNER_SLOTS,
    heat_level,
    runner_class,
    score_bars,
    slug,
    sql_anchor,
    statement_bars,
)
from jpa_bench_viz.results import (
    DATASET_ORDER,
    DB_LABELS,
    HIGHER_IS_BETTER,
    KNOWN_DBS,
    METRIC_LABELS,
    MODE_LABELS,
    Results,
    ordered,
    scope_label,
    statement_table,
    verification_mismatches,
)
from jpa_bench_viz.runinfo import DEFAULT_REPO_URL, summarize

STATEMENT_COLUMNS: tuple[str, ...] = (
    "stmtSelect",
    "stmtInsert",
    "stmtUpdate",
    "stmtDelete",
    "stmtOther",
    "stmtTotal",
    "jdbcExecutions",
    "jdbcBatches",
    "jdbcBatchedRows",
    "sqlChars",
    "selectMaxChars",
    "selectJoins",
    "jdbcConnections",
    "jdbcCommits",
    "jdbcRollbacks",
    "jdbcConnectionSettings",
    "jdbcPrepares",
)


@dataclass
class BenchmarkSection:
    name: str
    anchor: str
    bars: list[dict[str, Any]] = field(default_factory=list)
    sql: list[dict[str, str]] = field(default_factory=list)


@dataclass
class DatasetSection:
    db: str
    dataset: str
    title: str
    anchor: str
    score_tables: list[dict[str, Any]] = field(default_factory=list)
    statements: list[dict[str, Any]] = field(default_factory=list)
    statement_rows: list[dict[str, Any]] = field(default_factory=list)
    benchmarks: list[BenchmarkSection] = field(default_factory=list)

    @property
    def single(self) -> bool:
        """One benchmark (emfBootstrap): bars instead of a heatmap."""
        return len(self.benchmarks) == 1


def format_number(value: Any) -> str:
    if value is None:
        return ""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return str(value)
    if math.isnan(number):
        return "n/a"
    if number == 0:
        return "0"
    if abs(number) >= 1000:
        return f"{number:,.0f}"
    if abs(number) >= 100:
        return f"{number:.1f}"
    if abs(number) >= 1:
        return f"{number:.2f}"
    return f"{number:.3g}"


def environment() -> Environment:
    env = Environment(
        loader=PackageLoader("jpa_bench_viz", "templates"),
        autoescape=select_autoescape(["html", "j2"]),
        trim_blocks=True,
        lstrip_blocks=True,
    )
    env.filters["num"] = format_number
    env.filters["count"] = lambda n: f"{int(n):,}"
    env.filters["qty"] = lambda v: f"{v:,.2f}".rstrip("0").rstrip(".")
    env.filters["factor"] = lambda f: "n/a" if f is None or math.isnan(f) else f"{f:.2f}"
    env.globals["runner_slots"] = {runner_class(r): slot for r, slot in RUNNER_SLOTS.items()}
    return env


def build_report(
    results: Results,
    out_dir: Path,
    links: dict[str, str] | None = None,
    repo_url: str = DEFAULT_REPO_URL,
) -> Path:
    """Writes ``index.html``, ``scores.csv`` and ``statements.csv`` into ``out_dir``; returns the report.

    ``links`` (``index``, ``datasets``) are the relative URLs of the site pages, None for a standalone report.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    scores = results.scores
    labels = {r: results.provider_label(r) for r in results.runners}
    table = statement_table(results.metrics)

    if not scores.empty:
        scores.drop(columns=["sql_file"]).to_csv(out_dir / "scores.csv", index=False)
    if not table.empty:
        table.to_csv(out_dir / "statements.csv", index=False)

    sections: list[DatasetSection] = []
    dbs = ordered(set(scores["db"]), KNOWN_DBS) if not scores.empty else []
    for db in dbs:
        for dataset in ordered(set(scores[scores["db"] == db]["dataset"]), DATASET_ORDER):
            sections.append(_dataset_section(results, table, labels, db, dataset))

    env = environment()
    html = env.get_template("report.html.j2").render(
        run=summarize(results.directory),
        links=links,
        repo_url=repo_url,
        datasets_url=links["datasets"] if links else "",
        metadata=results.metadata,
        directory=results.directory,
        runners=results.runners,
        labels=labels,
        classes={r: runner_class(r) for r in results.runners},
        providers=_providers(results),
        sections=sections,
        statement_columns=[(c, METRIC_LABELS.get(c, c)) for c in STATEMENT_COLUMNS],
        db_labels=DB_LABELS,
        verification=_verification(results),
    )
    report = out_dir / "index.html"
    report.write_text(html, encoding="utf-8")
    return report


def _verification(results: Results) -> dict[str, Any] | None:
    """Result checksums per (db, dataset, operation) and runner; mismatching rows are flagged."""
    frame = results.verification
    if frame.empty:
        return None
    runners = [r for r in results.runners if r in set(frame["runner"])]
    runners += sorted(set(frame["runner"]) - set(runners))
    bad = verification_mismatches(frame)
    rows = []
    for (db, dataset, operation), group in frame.groupby(["db", "dataset", "operation"], sort=False):
        by_runner = group.groupby("runner")["checksum"].apply(lambda c: " / ".join(sorted(c))).to_dict()
        rows.append(
            {
                "scope": scope_label(str(db), str(dataset)),
                "operation": operation,
                "checksums": [by_runner.get(r, "") for r in runners],
                "mismatch": (str(db), str(dataset), str(operation)) in bad,
            }
        )
    rows.sort(key=lambda r: (not r["mismatch"], r["scope"], r["operation"]))
    return {"runners": runners, "rows": rows, "mismatches": len(bad)}


def _providers(results: Results) -> list[dict[str, Any]]:
    providers = []
    for runner in results.runners:
        info = results.metadata.get("runners", {}).get(runner, {})
        harness = info.get("harness", {})
        providers.append(
            {
                "runner": runner,
                "label": results.provider_label(runner),
                "harness": harness,
                "properties": info.get("providerProperties", {}),
            }
        )
    return providers


def _dataset_section(
    results: Results,
    table: pd.DataFrame,
    labels: dict[str, str],
    db: str,
    dataset: str,
) -> DatasetSection:
    scores = results.scores
    frame = scores[(scores["db"] == db) & (scores["dataset"] == dataset)]
    anchor = f"{db}-{dataset}"
    section = DatasetSection(db=db, dataset=dataset, title=scope_label(db, dataset), anchor=anchor)
    modes = ordered(set(frame["mode"]), ("thrpt", "avgt", "ss", "sample"))
    benchmarks = sorted(frame["benchmark"].unique())
    runners = [r for r in results.runners if r in set(frame["runner"])]

    for mode in modes:
        section.score_tables.append(
            _score_table(frame[frame["mode"] == mode], mode, benchmarks, runners, anchor)
        )

    if not table.empty:
        part = table[(table["db"] == db) & (table["dataset"] == dataset)]
        if not part.empty:
            section.statements = statement_bars(part, runners, labels)
        for benchmark in benchmarks:
            for runner in runners:
                rows = part[(part["benchmark"] == benchmark) & (part["runner"] == runner)]
                if rows.empty:
                    continue
                row = rows.iloc[0]
                section.statement_rows.append(
                    {
                        "benchmark": benchmark,
                        "runner": runner,
                        "values": {c: row[c] if c in row.index else None for c in STATEMENT_COLUMNS},
                    }
                )

    for benchmark in benchmarks:
        rows = frame[frame["benchmark"] == benchmark]
        sql = _sql_texts(results, frame, benchmark, runners, labels, anchor)
        bars = [
            score_bars(rows[rows["mode"] == m], runners, labels, anchor)
            for m in modes
            if m in set(rows["mode"])
        ]
        # link a bar to its SQL only if there is SQL
        with_sql = {q["anchor"] for q in sql}
        for chart in bars:
            for row in chart["rows"]:
                if row["sql"] not in with_sql:
                    row["sql"] = ""
        section.benchmarks.append(
            BenchmarkSection(name=benchmark, anchor=f"{anchor}-{slug(benchmark)}", bars=bars, sql=sql)
        )
    return section


def _score_table(
    frame: pd.DataFrame, mode: str, benchmarks: list[str], runners: list[str], section: str
) -> dict[str, Any]:
    rows = []
    for benchmark in [b for b in benchmarks if b in set(frame["benchmark"])]:
        cells = []
        for runner in runners:
            match = frame[(frame["benchmark"] == benchmark) & (frame["runner"] == runner)]
            if match.empty:
                cells.append(None)
                continue
            row = match.iloc[0]
            cells.append(
                {
                    "score": row["score"],
                    "error": row["error"],
                    "relative": row["relative"],
                    "level": heat_level(row["relative"]),
                    "best": bool(row["best"]),
                    "tie": bool(row["tie"]) and not bool(row["best"]),
                }
            )
        rows.append({"benchmark": benchmark, "anchor": f"{section}-{slug(benchmark)}", "cells": cells})
    unit = frame["unit"].iloc[0] if not frame.empty else ""
    better = "higher is better" if mode in HIGHER_IS_BETTER else "lower is better"
    return {
        "mode": mode,
        "runners": runners,
        "title": MODE_LABELS.get(mode, mode),
        "unit": unit,
        "better": better,
        "rows": rows,
    }


def _sql_texts(
    results: Results,
    frame: pd.DataFrame,
    benchmark: str,
    runners: list[str],
    labels: dict[str, str],
    section: str,
) -> list[dict[str, str]]:
    """Distinct SQL of the first iteration per runner (throughput run preferred, any mode otherwise)."""
    texts = []
    for runner in runners:
        rows = frame[(frame["benchmark"] == benchmark) & (frame["runner"] == runner)]
        rows = rows.assign(_pref=rows["mode"].map(lambda m: 0 if m == "thrpt" else 1)).sort_values("_pref")
        for _, row in rows.iterrows():
            path = results.sql_dir(runner, row["db"], _file_dataset(row)) / row["sql_file"]
            if path.is_file():
                texts.append(
                    {
                        "runner": runner,
                        "label": labels.get(runner, runner),
                        "anchor": sql_anchor(section, benchmark, runner),
                        "text": path.read_text(encoding="utf-8").strip(),
                    }
                )
                break
    return texts


def _file_dataset(row: pd.Series) -> str:
    """The dataset part of the file name the row came from (``bootstrap`` for the bootstrap run)."""
    stem = str(row["file"]).removesuffix(".json")
    return stem.rsplit("-", 1)[-1]
