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
"""Tests against a trimmed fixture of a real ``run.sh --smoke`` result directory (H2, dataset S)."""

from __future__ import annotations

import json
import math
import shutil
from pathlib import Path

import pytest

from jpa_bench_viz.cli import main
from jpa_bench_viz.results import load, sql_file_name, statement_table

FIXTURE = Path(__file__).parent / "fixtures" / "run"


def test_load_reads_scores_and_ignores_empty_files() -> None:
    results = load(FIXTURE)
    # the empty eclipselink file is an aborted run and must be skipped
    assert results.runners == ["runner-openjpa-4.2", "runner-hibernate-7"]
    scores = results.scores
    # 2 runners x (2 benchmarks x 2 modes + 1 bootstrap)
    assert len(scores) == 10
    assert set(scores["mode"]) == {"thrpt", "avgt", "ss"}
    assert set(scores["dataset"]) == {"S", "bootstrap"}
    assert set(scores["db"]) == {"h2"}
    # one iteration: JMH writes "NaN" as score error, which becomes 0
    assert (scores["error"] == 0).all()
    assert results.provider_label("runner-hibernate-7") == "Hibernate ORM 7.4.15.Final"


def test_relative_is_one_for_the_fastest_provider_in_both_modes() -> None:
    scores = load(FIXTURE).scores
    find = scores[(scores["benchmark"] == "findById") & (scores["dataset"] == "S")]
    thrpt = find[find["mode"] == "thrpt"].set_index("runner")
    avgt = find[find["mode"] == "avgt"].set_index("runner")
    # higher throughput is better: relative = score / best
    assert thrpt.at["runner-hibernate-7", "relative"] == 1.0
    expected = thrpt.at["runner-openjpa-4.2", "score"] / thrpt.at["runner-hibernate-7", "score"]
    assert thrpt.at["runner-openjpa-4.2", "relative"] == pytest.approx(expected)
    # lower time is better: relative = best / score
    assert avgt.at["runner-hibernate-7", "relative"] == 1.0
    expected = avgt.at["runner-hibernate-7", "score"] / avgt.at["runner-openjpa-4.2", "score"]
    assert avgt.at["runner-openjpa-4.2", "relative"] == pytest.approx(expected)
    assert (find["relative"] <= 1.0).all()


def test_statement_metrics_per_operation() -> None:
    table = statement_table(load(FIXTURE).metrics)
    row = table[(table["runner"] == "runner-hibernate-7") & (table["benchmark"] == "persistBatch")].iloc[0]
    # 50 orders with their lines, batched
    assert row["stmtInsert"] == 200
    assert row["stmtTotal"] == 200
    assert row["jdbcBatches"] > 0
    find = table[(table["runner"] == "runner-hibernate-7") & (table["benchmark"] == "findById")].iloc[0]
    assert find["stmtSelect"] == 1


def test_per_op_is_the_mean_of_raw_data_not_the_summed_score(tmp_path: Path) -> None:
    run = tmp_path / "run"
    shutil.copytree(FIXTURE, run)
    path = run / "runner-hibernate-7-h2-S.json"
    entries = json.loads(path.read_text(encoding="utf-8"))
    # two forks with two iterations: JMH sums EVENTS counters into the score
    entries[0]["secondaryMetrics"]["stmtSelect"]["rawData"] = [[1.0, 3.0], [2.0, 2.0]]
    entries[0]["secondaryMetrics"]["stmtSelect"]["score"] = 8.0
    path.write_text(json.dumps(entries), encoding="utf-8")
    metrics = load(run).metrics
    row = metrics[
        (metrics["runner"] == "runner-hibernate-7")
        & (metrics["benchmark"] == "findById")
        & (metrics["mode"] == "thrpt")
        & (metrics["metric"] == "stmtSelect")
    ].iloc[0]
    assert row["per_op"] == 2.0
    assert row["samples"] == 4


def test_sql_file_name_matches_bench_core() -> None:
    name = sql_file_name(
        "WriteBenchmarks", "persistBatch", "thrpt", {"db": "h2", "entities": "50", "dataset": "S"}
    )
    assert name == "WriteBenchmarks.persistBatch-thrpt-dataset=S-db=h2-entities=50.sql"
    assert (
        FIXTURE
        / "sql"
        / "runner-hibernate-7-h2-S"
        / sql_file_name(
            "ReadBenchmarks",
            "findById",
            "thrpt",
            {
                "db": "h2",
                "dataset": "S",
            },
        )
    ).is_file()


@pytest.mark.parametrize("image_format", ["svg", "png"])
def test_cli_writes_a_self_contained_report(tmp_path: Path, image_format: str) -> None:
    out = tmp_path / "report"
    assert main([str(FIXTURE), "--out", str(out), "--format", image_format]) == 0
    html = (out / "index.html").read_text(encoding="utf-8")
    assert "Hibernate ORM 7.4.15.Final" in html
    assert "Apache OpenJPA 4.2.0-SNAPSHOT" in html
    assert "findById" in html and "persistBatch" in html and "emfBootstrap" in html
    # the distinct SQL sidecar of findById is embedded
    assert "select" in html.lower()
    if image_format == "svg":
        assert "<svg" in html
    else:
        assert "data:image/png;base64," in html
    charts = {p.name for p in (out / "charts").iterdir()}
    assert f"relative-h2-S-thrpt.{image_format}" in charts
    assert f"statements-h2-S.{image_format}" in charts
    assert f"sql-shape-h2-S.{image_format}" in charts
    assert f"score-h2-bootstrap-emfBootstrap.{image_format}" in charts
    assert (out / "scores.csv").is_file()
    assert (out / "statements.csv").is_file()


def test_cli_fails_without_results(tmp_path: Path) -> None:
    assert main([str(tmp_path)]) == 1
    assert main([str(tmp_path / "missing")]) == 2


def test_relative_is_nan_for_a_zero_score(tmp_path: Path) -> None:
    run = tmp_path / "run"
    shutil.copytree(FIXTURE, run)
    path = run / "runner-openjpa-4.2-h2-S.json"
    entries = json.loads(path.read_text(encoding="utf-8"))
    entries[0]["primaryMetric"]["score"] = 0.0
    path.write_text(json.dumps(entries), encoding="utf-8")
    scores = load(run).scores
    zero = scores[(scores["runner"] == "runner-openjpa-4.2") & (scores["score"] == 0.0)].iloc[0]
    assert math.isnan(zero["relative"])
