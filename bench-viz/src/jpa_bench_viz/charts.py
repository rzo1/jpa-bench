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
"""Static matplotlib charts (SVG or PNG) for one results directory."""

from __future__ import annotations

import io
import math
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402
from matplotlib.axes import Axes  # noqa: E402
from matplotlib.figure import Figure  # noqa: E402

from jpa_bench_viz.results import (  # noqa: E402
    HIGHER_IS_BETTER,
    KNOWN_RUNNERS,
    MODE_LABELS,
    STATEMENT_KINDS,
    STATEMENT_LABELS,
    scope_label,
)

# Categorical palette in fixed slot order (validated for adjacent pairs, light surface).
SERIES_COLORS: tuple[str, ...] = (
    "#2a78d6",
    "#eb6834",
    "#1baf7a",
    "#eda100",
    "#e87ba4",
    "#008300",
    "#6250d6",
    "#e34948",
)
FALLBACK_COLOR = "#8a8984"
SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#8a8984"
GRID = "#e4e3df"

plt.rcParams.update(
    {
        "font.family": "sans-serif",
        "font.size": 9,
        "svg.fonttype": "none",
        "figure.facecolor": SURFACE,
        "axes.facecolor": SURFACE,
        "axes.edgecolor": GRID,
        "axes.labelcolor": INK_SECONDARY,
        "axes.titlecolor": INK,
        "axes.titlesize": 10,
        "axes.titleweight": "bold",
        "axes.titlelocation": "left",
        "axes.spines.top": False,
        "axes.spines.right": False,
        "xtick.color": INK_SECONDARY,
        "ytick.color": INK_SECONDARY,
        "legend.frameon": False,
        "legend.labelcolor": INK_SECONDARY,
    }
)


def runner_color(runner: str, runners: Sequence[str]) -> str:
    """The colour of a runner follows its fixed position among the known runners, never its rank."""
    order = list(KNOWN_RUNNERS) + [r for r in runners if r not in KNOWN_RUNNERS]
    index = order.index(runner) if runner in order else len(SERIES_COLORS)
    return SERIES_COLORS[index] if index < len(SERIES_COLORS) else FALLBACK_COLOR


@dataclass(frozen=True)
class Chart:
    """A rendered chart: file name relative to the output directory, inline markup and alt text."""

    name: str
    path: Path
    inline: str
    alt: str


class ChartWriter:
    def __init__(self, out_dir: Path, image_format: str = "svg") -> None:
        if image_format not in ("svg", "png"):
            raise ValueError(f"unsupported image format: {image_format}")
        self.out_dir = out_dir
        self.image_format = image_format
        out_dir.mkdir(parents=True, exist_ok=True)

    def save(self, figure: Figure, name: str, alt: str) -> Chart:
        path = self.out_dir / f"{name}.{self.image_format}"
        figure.savefig(path, format=self.image_format, dpi=150, bbox_inches="tight")
        inline = ""
        if self.image_format == "svg":
            buffer = io.StringIO()
            figure.savefig(buffer, format="svg", bbox_inches="tight")
            svg = buffer.getvalue()
            inline = svg[svg.index("<svg") :]
        plt.close(figure)
        return Chart(name=name, path=path, inline=inline, alt=alt)


def _style_axis(ax: Axes, axis: str = "x") -> None:
    ax.grid(axis=axis, color=GRID, linewidth=0.8)
    ax.set_axisbelow(True)
    ax.tick_params(length=0)
    for side in ("left", "bottom"):
        ax.spines[side].set_color(GRID)


def _legend(fig: Figure, ax: Axes, ncol: int) -> None:
    handles, labels = ax.get_legend_handles_labels()
    if len(handles) > 1:
        fig.legend(handles, labels, loc="upper left", bbox_to_anchor=(0.0, 0.0), ncol=ncol, fontsize=8)


def _format(value: float) -> str:
    if value is None or math.isnan(value):
        return "n/a"
    if value == 0:
        return "0"
    magnitude = abs(value)
    if magnitude < 1000 and abs(value - round(value)) < 0.005:
        return f"{round(value):d}"
    if magnitude >= 1000:
        return f"{value:,.0f}"
    if magnitude >= 10:
        return f"{value:.1f}"
    if magnitude >= 0.01:
        return f"{value:.2f}"
    return f"{value:.2e}"


def grouped_bars(
    ax: Axes,
    frame: pd.DataFrame,
    categories: list[str],
    runners: list[str],
    all_runners: Sequence[str],
    value: str,
    error: str | None,
    labels: dict[str, str],
) -> None:
    """Horizontal bars: one group per category (top to bottom), one bar per runner."""
    count = max(len(runners), 1)
    band = 0.8
    height = band / count
    for i, runner in enumerate(runners):
        rows = frame[frame["runner"] == runner].set_index("category")
        ys: list[float] = []
        xs: list[float] = []
        errs: list[float] = []
        for c, category in enumerate(categories):
            if category not in rows.index:
                continue
            row = rows.loc[category]
            x = float(row[value])
            if math.isnan(x):
                continue
            ys.append(c - band / 2 + (i + 0.5) * height)
            xs.append(x)
            errs.append(float(row[error]) if error and not math.isnan(float(row[error])) else 0.0)
        if not xs:
            continue
        ax.barh(
            ys,
            xs,
            height=height * 0.88,
            color=runner_color(runner, all_runners),
            label=labels.get(runner, runner),
            xerr=errs if error and any(errs) else None,
            error_kw={"ecolor": INK_SECONDARY, "elinewidth": 0.8, "capsize": 1.5},
        )
    ax.set_yticks(range(len(categories)), categories)
    ax.set_ylim(len(categories) - 0.5, -0.5)


def relative_overview(
    writer: ChartWriter,
    scores: pd.DataFrame,
    db: str,
    dataset: str,
    mode: str,
    all_runners: Sequence[str],
    labels: dict[str, str],
) -> Chart | None:
    """All benchmarks of one database, dataset and mode, normalized to the fastest provider (1.0)."""
    frame = scores[(scores["db"] == db) & (scores["dataset"] == dataset) & (scores["mode"] == mode)]
    if frame.empty:
        return None
    frame = frame.assign(category=frame["benchmark"])
    categories = sorted(frame["benchmark"].unique())
    runners = [r for r in all_runners if r in set(frame["runner"])]
    fig, ax = plt.subplots(figsize=(8.5, max(2.6, 0.5 + 0.17 * len(categories) * max(len(runners), 2))))
    grouped_bars(ax, frame, categories, runners, all_runners, "relative", "relative_error", labels)
    _style_axis(ax)
    ax.set_xlim(0, 1.08)
    ax.axvline(1.0, color=INK_MUTED, linewidth=0.8, linestyle=(0, (2, 2)))
    direction = "throughput" if mode in HIGHER_IS_BETTER else "speed (fastest time / time)"
    ax.set_xlabel(f"relative {direction}, 1.0 = fastest provider")
    ax.set_title(f"{scope_label(db, dataset)}: {MODE_LABELS.get(mode, mode).lower()}")
    fig.tight_layout()
    _legend(fig, ax, ncol=min(len(runners), 3))
    alt = (
        f"Relative {MODE_LABELS.get(mode, mode).lower()} of every benchmark on {db}, dataset {dataset}, "
        "normalized to the fastest provider"
    )
    return writer.save(fig, f"relative-{db}-{dataset}-{mode}", alt)


def benchmark_scores(
    writer: ChartWriter,
    scores: pd.DataFrame,
    db: str,
    dataset: str,
    benchmark: str,
    all_runners: Sequence[str],
    labels: dict[str, str],
) -> Chart | None:
    """Absolute score with error bars per mode for one benchmark, labelled with the factor to the fastest."""
    frame = scores[(scores["db"] == db) & (scores["dataset"] == dataset) & (scores["benchmark"] == benchmark)]
    if frame.empty:
        return None
    modes = [m for m in ("thrpt", "avgt", "ss", "sample") if m in set(frame["mode"])]
    modes += sorted(set(frame["mode"]) - set(modes))
    runners = [r for r in all_runners if r in set(frame["runner"])]
    fig, axes = plt.subplots(
        1,
        len(modes),
        figsize=(5.2 * len(modes) + 1.5, 0.9 + 0.32 * len(runners)),
        squeeze=False,
        sharey=True,
    )
    for ax, mode in zip(axes[0], modes, strict=True):
        part = frame[frame["mode"] == mode]
        for c, runner in enumerate(runners):
            match = part[part["runner"] == runner]
            if match.empty:
                continue
            row = match.iloc[0]
            ax.barh(
                c,
                row["score"],
                height=0.7,
                color=runner_color(row["runner"], all_runners),
                xerr=row["error"] if row["error"] else None,
                error_kw={"ecolor": INK_SECONDARY, "elinewidth": 0.8, "capsize": 2},
            )
            factor = row["relative"]
            if math.isnan(factor) or not factor:
                text = ""
            elif factor == 1.0:
                text = "  (fastest)"
            else:
                text = f"  ({1 / factor:.2f}x slower)"
            ax.text(
                row["score"] + (row["error"] or 0),
                c,
                f"  {_format(row['score'])}{text}",
                va="center",
                fontsize=7.5,
                color=INK_SECONDARY,
            )
        ax.set_yticks(range(len(runners)), [labels.get(r, r) for r in runners])
        ax.set_ylim(len(runners) - 0.5, -0.5)
        _style_axis(ax)
        ax.margins(x=0.6)
        ax.set_xlim(left=0)
        better = "higher is better" if mode in HIGHER_IS_BETTER else "lower is better"
        unit = part["unit"].iloc[0]
        ax.set_xlabel(f"{unit} ({better})")
        ax.set_title(MODE_LABELS.get(mode, mode))
    fig.suptitle(
        f"{benchmark}: {scope_label(db, dataset)}",
        x=0.0,
        ha="left",
        fontsize=10,
        fontweight="bold",
        color=INK,
    )
    fig.tight_layout()
    safe = benchmark.replace(" ", "").replace("[", "-").replace("]", "").replace("=", "-").replace(",", "-")
    alt = f"Score of {benchmark} per provider on {db}, dataset {dataset}, with error bars"
    return writer.save(fig, f"score-{db}-{dataset}-{safe}", alt)


def statements_per_op(
    writer: ChartWriter,
    table: pd.DataFrame,
    db: str,
    dataset: str,
    all_runners: Sequence[str],
    labels: dict[str, str],
) -> Chart | None:
    """Small multiples, one panel per benchmark: statements per operation stacked by statement kind."""
    frame = table[(table["db"] == db) & (table["dataset"] == dataset)]
    kinds = [k for k in STATEMENT_KINDS if k in frame.columns]
    if frame.empty or not kinds:
        return None
    benchmarks = sorted(frame["benchmark"].unique())
    runners = [r for r in all_runners if r in set(frame["runner"])]
    columns = 3
    rows = math.ceil(len(benchmarks) / columns)
    fig, axes = plt.subplots(
        rows,
        columns,
        figsize=(13, 0.4 + rows * (0.55 + 0.24 * len(runners))),
        squeeze=False,
        sharey=True,
    )
    kind_colors = dict(zip(STATEMENT_KINDS, SERIES_COLORS, strict=False))
    for index, ax in enumerate(axes.flat):
        if index >= len(benchmarks):
            ax.set_visible(False)
            continue
        benchmark = benchmarks[index]
        part = frame[frame["benchmark"] == benchmark].set_index("runner")
        left = [0.0] * len(runners)
        for kind in kinds:
            values = [
                float(part.at[r, kind]) if r in part.index and not math.isnan(part.at[r, kind]) else 0.0
                for r in runners
            ]
            ax.barh(
                range(len(runners)),
                values,
                left=left,
                height=0.7,
                color=kind_colors[kind],
                edgecolor=SURFACE,
                linewidth=1,
                label=STATEMENT_LABELS[kind],
            )
            left = [a + b for a, b in zip(left, values, strict=True)]
        for y, total in enumerate(left):
            ax.text(total, y, f" {_format(total)}", va="center", fontsize=7, color=INK_SECONDARY)
        ax.set_yticks(range(len(runners)), [labels.get(r, r) for r in runners])
        ax.set_ylim(len(runners) - 0.5, -0.5)
        ax.margins(x=0.22)
        ax.set_xlim(left=0)
        _style_axis(ax)
        ax.set_title(benchmark, fontsize=9)
    fig.suptitle(
        f"Statements per operation by kind: {scope_label(db, dataset)}",
        x=0.0,
        ha="left",
        fontsize=10,
        fontweight="bold",
        color=INK,
    )
    fig.tight_layout()
    _legend(fig, axes.flat[0], ncol=len(kinds))
    alt = f"Statements per operation stacked by kind, one panel per benchmark, on {db}, dataset {dataset}"
    return writer.save(fig, f"statements-{db}-{dataset}", alt)


# (metric, panel title, log scale); counts spanning orders of magnitude use a log scale
SQL_SHAPE_METRICS: tuple[tuple[str, str, bool], ...] = (
    ("jdbcExecutions", "JDBC round trips / op (log)", True),
    ("sqlChars", "SQL chars / op (log)", True),
    ("selectMaxChars", "longest SELECT (chars)", False),
    ("selectJoins", "joined tables in SELECTs / op", False),
)


def sql_shape(
    writer: ChartWriter,
    table: pd.DataFrame,
    db: str,
    dataset: str,
    all_runners: Sequence[str],
    labels: dict[str, str],
) -> Chart | None:
    """Round trips, SQL length, longest SELECT and joined tables per operation, log scale, per benchmark."""
    frame = table[(table["db"] == db) & (table["dataset"] == dataset)]
    metrics = [(m, title, log) for m, title, log in SQL_SHAPE_METRICS if m in frame.columns]
    if frame.empty or not metrics:
        return None
    frame = frame.assign(category=frame["benchmark"])
    categories = sorted(frame["benchmark"].unique())
    runners = [r for r in all_runners if r in set(frame["runner"])]
    fig, axes = plt.subplots(
        1,
        len(metrics),
        figsize=(3.4 * len(metrics) + 1.5, max(2.6, 0.6 + 0.15 * len(categories) * max(len(runners), 2))),
        sharey=True,
        squeeze=False,
    )
    for ax, (metric, title, log) in zip(axes[0], metrics, strict=True):
        positive = frame.assign(**{metric: frame[metric].where(frame[metric] > 0)})
        grouped_bars(ax, positive, categories, runners, all_runners, metric, None, labels)
        if log and positive[metric].notna().any():
            ax.set_xscale("log")
        else:
            ax.set_xlim(left=0)
        _style_axis(ax)
        ax.set_title(title, fontsize=9)
    fig.suptitle(
        f"SQL shape per operation (missing bar = 0): {scope_label(db, dataset)}",
        x=0.0,
        ha="left",
        fontsize=10,
        fontweight="bold",
        color=INK,
    )
    fig.tight_layout()
    _legend(fig, axes[0][0], ncol=min(len(runners), 3))
    alt = (
        "JDBC round trips, SQL length, longest SELECT and joined tables per operation"
        f" on {db}, dataset {dataset}"
    )
    return writer.save(fig, f"sql-shape-{db}-{dataset}", alt)
