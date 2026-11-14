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
"""Static site of many runs: ``index.html``, ``datasets.html`` and ``runs/<run-id>/index.html``."""

from __future__ import annotations

import shutil
from datetime import UTC, datetime
from pathlib import Path

from jpa_bench_viz.datasets import reference
from jpa_bench_viz.report import build_report, environment
from jpa_bench_viz.results import load
from jpa_bench_viz.runinfo import DEFAULT_REPO_URL, RunInfo, summarize

RUN_FILES = ("metadata.json", "execution.tsv", "verification.tsv", "datasets.tsv")


def run_dirs(runs_dir: Path) -> list[Path]:
    return sorted(p for p in runs_dir.iterdir() if p.is_dir() and (p / "metadata.json").is_file())


def build_site(
    runs_dir: Path, out_dir: Path, image_format: str = "svg", repo_url: str = DEFAULT_REPO_URL
) -> list[RunInfo]:
    """Renders every run of ``runs_dir`` plus the index and the dataset page; returns the runs."""
    out_dir.mkdir(parents=True, exist_ok=True)
    runs: list[RunInfo] = []
    for directory in run_dirs(runs_dir):
        results = load(directory)
        if results.scores.empty:
            continue
        target = out_dir / "runs" / directory.name
        if target.exists():
            shutil.rmtree(target)
        build_report(
            results,
            target,
            image_format,
            links={"index": "../../index.html", "datasets": "../../datasets.html"},
            repo_url=repo_url,
        )
        for name in RUN_FILES:
            if (directory / name).is_file():
                shutil.copy2(directory / name, target / name)
        runs.append(summarize(directory))
    runs.sort(key=lambda r: (r.started or datetime.min.replace(tzinfo=UTC), r.run_id), reverse=True)

    env = environment()
    generated = datetime.now(UTC).strftime("%Y-%m-%d %H:%M UTC")
    (out_dir / "index.html").write_text(
        env.get_template("index.html.j2").render(
            comparable=[r for r in runs if r.comparable],
            other=[r for r in runs if not r.comparable],
            repo_url=repo_url,
            generated=generated,
        ),
        encoding="utf-8",
    )
    (out_dir / "datasets.html").write_text(
        env.get_template("datasets.html.j2").render(data=reference(), runs=runs, repo_url=repo_url),
        encoding="utf-8",
    )
    (out_dir / ".nojekyll").write_text("", encoding="utf-8")
    return runs
