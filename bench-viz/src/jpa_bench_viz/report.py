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
"""Builds the charts and the static HTML report of one results directory."""

from __future__ import annotations

import base64
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import pandas as pd
from jinja2 import Environment, PackageLoader, select_autoescape
from markupsafe import Markup

from jpa_bench_viz import charts
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
)

# Columns of the statement table, in display order.
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
)


@dataclass
class BenchmarkSection:
    name: str
    chart: charts.Chart | None
    sql: list[tuple[str, str]] = field(default_factory=list)


@dataclass
class DatasetSection:
    db: str
    dataset: str
    title: str
    anchor: str
    overview: list[charts.Chart] = field(default_factory=list)
    score_tables: list[dict[str, Any]] = field(default_factory=list)
    statements: charts.Chart | None = None
    sql_shape: charts.Chart | None = None
    statement_rows: list[dict[str, Any]] = field(default_factory=list)
    benchmarks: list[BenchmarkSection] = field(default_factory=list)


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


def build_report(results: Results, out_dir: Path, image_format: str = "svg") -> Path:
    """Writes charts, CSV exports and ``index.html`` into ``out_dir``; returns the path of the report."""
    out_dir.mkdir(parents=True, exist_ok=True)
    writer = charts.ChartWriter(out_dir / "charts", image_format)
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
            sections.append(_dataset_section(results, writer, table, labels, db, dataset))

    environment = Environment(
        loader=PackageLoader("jpa_bench_viz", "templates"),
        autoescape=select_autoescape(["html", "j2"]),
        trim_blocks=True,
        lstrip_blocks=True,
    )
    environment.filters["num"] = format_number
    environment.globals["chart"] = lambda c: _embed(c, out_dir)
    template = environment.get_template("report.html.j2")
    html = template.render(
        metadata=results.metadata,
        directory=results.directory,
        runners=results.runners,
        labels=labels,
        providers=_providers(results),
        sections=sections,
        statement_columns=[(c, METRIC_LABELS.get(c, c)) for c in STATEMENT_COLUMNS],
        db_labels=DB_LABELS,
    )
    report = out_dir / "index.html"
    report.write_text(html, encoding="utf-8")
    return report


def _embed(chart: charts.Chart | None, out_dir: Path) -> Markup:
    """Inline SVG, or a PNG as data URI, so ``index.html`` is a single self-contained file."""
    if chart is None:
        return Markup("")
    if chart.inline:
        return Markup('<figure class="chart" role="img" aria-label="{}">{}</figure>').format(
            chart.alt, Markup(chart.inline)
        )
    data = base64.b64encode(chart.path.read_bytes()).decode("ascii")
    return Markup('<figure class="chart"><img alt="{}" src="data:image/png;base64,{}"></figure>').format(
        chart.alt, data
    )


def _providers(results: Results) -> list[dict[str, Any]]:
    providers = []
    for runner in results.runners:
        info = results.metadata.get("runners", {}).get(runner, {})
        harness = info.get("harness", {})
        providers.append(
            {
                "runner": runner,
                "label": results.provider_label(runner),
                "build": harness.get("bench.provider.build", ""),
                "revision": harness.get("bench.provider.revision", ""),
                "enhancement": harness.get("bench.provider.enhancement", ""),
                "datasource": harness.get("bench.datasource.keys", ""),
                "harness": harness,
                "properties": info.get("providerProperties", {}),
            }
        )
    return providers


def _dataset_section(
    results: Results,
    writer: charts.ChartWriter,
    table: pd.DataFrame,
    labels: dict[str, str],
    db: str,
    dataset: str,
) -> DatasetSection:
    scores = results.scores
    frame = scores[(scores["db"] == db) & (scores["dataset"] == dataset)]
    title = scope_label(db, dataset)
    section = DatasetSection(db=db, dataset=dataset, title=title, anchor=f"{db}-{dataset}")
    modes = ordered(set(frame["mode"]), ("thrpt", "avgt", "ss", "sample"))
    benchmarks = sorted(frame["benchmark"].unique())
    runners = [r for r in results.runners if r in set(frame["runner"])]

    for mode in modes:
        if len(benchmarks) > 1:
            chart = charts.relative_overview(writer, scores, db, dataset, mode, results.runners, labels)
            if chart is not None:
                section.overview.append(chart)
        section.score_tables.append(_score_table(frame[frame["mode"] == mode], mode, benchmarks, runners))

    if not table.empty:
        section.statements = charts.statements_per_op(writer, table, db, dataset, results.runners, labels)
        section.sql_shape = charts.sql_shape(writer, table, db, dataset, results.runners, labels)
        part = table[(table["db"] == db) & (table["dataset"] == dataset)]
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
        chart = charts.benchmark_scores(writer, scores, db, dataset, benchmark, results.runners, labels)
        section.benchmarks.append(
            BenchmarkSection(name=benchmark, chart=chart, sql=_sql_texts(results, frame, benchmark, runners))
        )
    return section


def _score_table(frame: pd.DataFrame, mode: str, benchmarks: list[str], runners: list[str]) -> dict[str, Any]:
    rows = []
    for benchmark in benchmarks:
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
                    "best": bool(row["relative"] == 1.0),
                }
            )
        rows.append({"benchmark": benchmark, "cells": cells})
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
    results: Results, frame: pd.DataFrame, benchmark: str, runners: list[str]
) -> list[tuple[str, str]]:
    """Distinct SQL of the first iteration per runner (throughput run preferred, any mode otherwise)."""
    texts = []
    for runner in runners:
        rows = frame[(frame["benchmark"] == benchmark) & (frame["runner"] == runner)]
        rows = rows.assign(_pref=rows["mode"].map(lambda m: 0 if m == "thrpt" else 1)).sort_values("_pref")
        for _, row in rows.iterrows():
            path = results.sql_dir(runner, row["db"], _file_dataset(row)) / row["sql_file"]
            if path.is_file():
                texts.append((runner, path.read_text(encoding="utf-8").strip()))
                break
    return texts


def _file_dataset(row: pd.Series) -> str:
    """The dataset part of the file name the row came from (``bootstrap`` for the bootstrap run)."""
    stem = str(row["file"]).removesuffix(".json")
    return stem.rsplit("-", 1)[-1]
