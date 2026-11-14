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
"""Tests of the site, validate and add-run commands and of datasets.json."""

from __future__ import annotations

import gzip
import json
import re
import shutil
from pathlib import Path
from typing import Any

import pytest

from jpa_bench_viz.addrun import add_run, run_id
from jpa_bench_viz.cli import main
from jpa_bench_viz.datasets import loaded, reference
from jpa_bench_viz.runinfo import run_kind, summarize
from jpa_bench_viz.validate import validate

FIXTURE = Path(__file__).parent / "fixtures" / "run"
REPO = Path(__file__).parents[2]
DATASET_LINE = (
    "# jpa-bench: dataset S seed=20260101 checksum={checksum} records=8525 (categories=25, products=500, "
    "customers=1000, orders=5000, orderLines=14922, stPersons=1000, jnPersons=1000) loaded in 229 ms\n"
)
FULL_JMH = {"forks": 2, "warmupIterations": 5, "iterations": 5, "warmupTime": "10s", "time": "10s"}


def make_run(path: Path, *, full: bool = True, checksum: str = "17f556d4e3d90c3d", **metadata: Any) -> Path:
    """A complete run from the fixture: execution.tsv, verification.tsv, a log with the dataset line."""
    shutil.copytree(FIXTURE, path)
    (path / "runner-eclipselink-5-h2-S.json").unlink()
    meta = json.loads((path / "metadata.json").read_text(encoding="utf-8"))
    meta["contributor"] = "octocat"
    meta["machine"] = {"model": "MacBook Pro", "vendor": "Apple", "power": "AC Power", "lowPowerMode": False}
    meta["cpu"]["model"] = "Apple M4 Max"
    meta["matrix"]["bootstrap"] = False
    if full:
        meta["jmh"].update(FULL_JMH)
    meta.update(metadata)
    (path / "metadata.json").write_text(json.dumps(meta), encoding="utf-8")
    rows = ["index\tkind\trunner\tdb\tdataset\tstart\tend\tstatus"]
    for i, runner in enumerate(meta["matrix"]["runners"].split(), 1):
        rows.append(f"{i}\tbench\t{runner}\th2\tS\t2026-11-14T15:00:00Z\t2026-11-14T16:30:00Z\tok")
    (path / "execution.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    verification = ["runner\tdb\tdataset\toperation\tchecksum"]
    for runner in meta["matrix"]["runners"].split():
        verification.append(f"{runner}\th2\tS\tfindById\t00000000000000aa")
    (path / "verification.tsv").write_text("\n".join(verification) + "\n", encoding="utf-8")
    (path / "logs").mkdir()
    (path / "logs" / "runner-hibernate-7-h2-S.log").write_text(
        "noise\n" + DATASET_LINE.format(checksum=checksum), encoding="utf-8"
    )
    return path


def test_run_kind() -> None:
    assert run_kind({"smoke": True, "jmh": FULL_JMH}) == "smoke"
    assert run_kind({"jmh": {"forks": 1, "iterations": 1}}) == "smoke"
    assert run_kind({"jmh": {"forks": 1, "iterations": 3, "warmupIterations": 2}}) == "reduced"
    assert run_kind({"jmh": FULL_JMH}) == "full"


def test_summary_of_a_run(tmp_path: Path) -> None:
    info = summarize(make_run(tmp_path / "run"))
    assert info.kind == "full" and info.comparable
    assert info.machine == "MacBook Pro, Apple M4 Max"
    assert info.duration == "1 h 30 min"
    assert info.jobs_complete and info.mismatches == 0
    assert [(d.size, d.matches) for d in info.loaded] == [("S", True)]
    openjpa = next(p for p in info.providers if p.runner == "runner-openjpa-4.2")
    assert openjpa.revision_url == "https://github.com/apache/openjpa/commit/50897c9"
    assert not info.notes


def test_datasets_from_logs_and_from_datasets_tsv(tmp_path: Path) -> None:
    run = make_run(tmp_path / "run")
    assert loaded(run)[0].counts["orderLines"] == "14922"
    (run / "datasets.tsv").write_text(
        "runner\tdb\tdataset\tseed\tchecksum\trecords\tcounts\n"
        "r1\th2\tS\t20260101\t17f556d4e3d90c3d\t8525\torders=5000\n"
        "r2\th2\tS\t20260101\t0000000000000000\t8525\torders=5000\n",
        encoding="utf-8",
    )
    found = loaded(run)
    assert [d.matches for d in found] == [False, True] or [d.matches for d in found] == [True, False]
    report = validate(run)
    assert any("is not 17f556d4e3d90c3d" in e for e in report.errors)
    assert "runners loaded different data for the same dataset" in report.errors


def test_validate_accepts_a_complete_run_and_rejects_broken_ones(tmp_path: Path) -> None:
    good = make_run(tmp_path / "2026-11-14-macbook-pro-m4-max-octocat")
    assert validate(good).ok, validate(good).errors
    # uncompressed logs are not accepted on the results branch
    assert not validate(good, submission=True).ok
    assert main(["validate", str(good)]) == 0

    bad = make_run(tmp_path / "bad", contributor="")
    (bad / "runner-hibernate-7-h2-S.json").write_text("", encoding="utf-8")
    with (bad / "verification.tsv").open("a", encoding="utf-8") as f:
        f.write("runner-openjpa-4.2\th2\tS\tjoinFetch\t01\nrunner-hibernate-7\th2\tS\tjoinFetch\t02\n")
    errors = " | ".join(validate(bad, submission=True).errors)
    assert "contributor not set" in errors
    assert "runner-hibernate-7-h2-S.json: empty" in errors
    assert "1 verification mismatch(es)" in errors
    assert "is not <yyyy-mm-dd>-<machine>-<contributor>" in errors
    assert main(["validate", str(tmp_path)]) == 1

    assert not validate(tmp_path / "missing-dir").ok


def test_validate_reports_incomplete_runs(tmp_path: Path) -> None:
    run = make_run(tmp_path / "run")
    lines = (run / "execution.tsv").read_text(encoding="utf-8").splitlines()
    (run / "execution.tsv").write_text("\n".join(lines[:-1]) + "\n", encoding="utf-8")
    assert "incomplete run: 1 of 2 jobs ok, 0 failed" in validate(run).errors


def test_add_run_names_the_run_and_gzips_the_logs(tmp_path: Path) -> None:
    raw = make_run(tmp_path / "raw", contributor="")
    (raw / "report").mkdir()
    (raw / "report" / "index.html").write_text("x", encoding="utf-8")
    runs = tmp_path / "runs"
    with pytest.raises(ValueError):
        add_run(raw, runs)
    first = add_run(raw, runs, contributor="Octo Cat")
    assert first.name == "2026-11-14-macbook-pro-m4-max-octo-cat"
    assert not (first / "report").exists()
    assert not list((first / "logs").glob("*.log"))
    with gzip.open(first / "logs" / "runner-hibernate-7-h2-S.log.gz", "rt", encoding="utf-8") as f:
        assert "checksum=17f556d4e3d90c3d" in f.read()
    assert json.loads((first / "metadata.json").read_text(encoding="utf-8"))["contributor"] == "Octo Cat"
    assert validate(first, submission=True).ok, validate(first, submission=True).errors
    # the dataset line is read from the gzipped log
    assert loaded(first)[0].matches is True

    second = add_run(raw, runs, contributor="x", machine="Build Box 1")
    assert second.name == "2026-11-14-build-box-1-x"
    assert add_run(raw, runs, contributor="x", machine="Build Box 1").name == "2026-11-14-build-box-1-x-2"
    assert run_id({"timestamp": "20261114T150304Z", "cpu": {"model": "Apple M5 Max"}}) == "2026-11-14-m5-max"


EXTERNAL_RESOURCE = re.compile(
    r"""<(?:script|link|img|iframe|source|embed|object)\b[^>]*\b(?:src|href|data)\s*=\s*["']?\s*(?:https?:)?//"""
    r"""|@import\s+(?:url\()?\s*["']?\s*(?:https?:)?//"""
    r"""|url\(\s*["']?\s*(?:https?:)?//""",
    re.IGNORECASE,
)


def test_site_is_self_contained_and_flags_smoke_runs(tmp_path: Path) -> None:
    runs = tmp_path / "runs"
    runs.mkdir()
    make_run(runs / "2026-11-14-macbook-pro-m4-max-octocat")
    make_run(runs / "2026-11-13-smoke-octocat", full=False, smoke=True, timestamp="20261113T100000Z")
    out = tmp_path / "site"
    assert main(["site", str(runs), "-o", str(out)]) == 0

    index = (out / "index.html").read_text(encoding="utf-8")
    assert index.index("2026-11-14-macbook-pro-m4-max-octocat") < index.index("Smoke and reduced runs")
    assert index.index("Smoke and reduced runs") < index.index("2026-11-13-smoke-octocat")
    assert 'href="runs/2026-11-14-macbook-pro-m4-max-octocat/index.html"' in index

    page = (out / "runs" / "2026-11-14-macbook-pro-m4-max-octocat" / "index.html").read_text(encoding="utf-8")
    assert '<a href="https://github.com/apache/openjpa/commit/50897c9">' in page
    assert "4.2.0-20261114.035154-55" in page
    assert "MacBook Pro, Apple M4 Max" in page
    assert '<a href="../../datasets.html#dataset-S">S</a>' in page
    assert "reference data" in page
    assert "all providers return the same results" in page
    smoke = (out / "runs" / "2026-11-13-smoke-octocat" / "index.html").read_text(encoding="utf-8")
    assert "Smoke run" in smoke and "Not comparable" in smoke

    datasets = (out / "datasets.html").read_text(encoding="utf-8")
    assert "17f556d4e3d90c3d" in datasets and "JOINED" in datasets and "14,922" in datasets

    pages = list(out.rglob("*.html")) + list(out.rglob("*.css")) + list(out.rglob("*.svg"))
    assert len(pages) > 3
    for path in pages:
        text = path.read_text(encoding="utf-8")
        assert not EXTERNAL_RESOURCE.search(text), f"external resource in {path}"


def test_external_resource_pattern() -> None:
    assert EXTERNAL_RESOURCE.search('<script src="https://cdn.example/x.js"></script>')
    assert EXTERNAL_RESOURCE.search("<link rel=stylesheet href=//fonts.example/css>")
    assert EXTERNAL_RESOURCE.search("@import url('https://fonts.example/css');")
    assert EXTERNAL_RESOURCE.search('<img alt="" src="http://x/y.png">')
    assert not EXTERNAL_RESOURCE.search('<a href="https://github.com/apache/openjpa/commit/1">')
    assert not EXTERNAL_RESOURCE.search('<img src="data:image/png;base64,AA">')


@pytest.mark.skipif(not (REPO / "bench-data").is_dir(), reason="needs the jpa-bench source tree")
def test_datasets_json_matches_the_sources() -> None:
    data = reference()
    java = REPO / "bench-data" / "src" / "main" / "java" / "io" / "github" / "rzo1" / "jpabench" / "data"
    size = (java / "DatasetSize.java").read_text(encoding="utf-8")
    scales = dict(re.findall(r"^\s+([SML])\((\d+)\)", size, re.MULTILINE))
    assert {k: v["scale"] for k, v in data["sizes"].items()} == {k: int(v) for k, v in scales.items()}
    categories = int(re.search(r"CATEGORIES = (\d+)", size)[1])
    products = int(re.search(r"return (\d+) \* scale;\s+}\s+public int customers", size)[1])
    customers = int(re.search(r"customers\(\) \{\s+return ([\d_]+) \* scale", size)[1].replace("_", ""))
    per_customer = int(re.search(r"orders\(\) \{\s+return (\d+) \* customers", size)[1])
    persons = int(re.search(r"persons\(\) \{\s+return ([\d_]+) \* scale", size)[1].replace("_", ""))
    for name, entry in data["sizes"].items():
        scale, tables = entry["scale"], entry["tables"]
        assert tables["CATEGORY"] == categories, name
        assert tables["PRODUCT"] == products * scale, name
        assert tables["CUSTOMER"] == customers * scale, name
        assert tables["ORDERS"] == per_customer * customers * scale, name
        assert tables["ST_PERSON"] == tables["JN_PERSON"] == persons * scale, name
        assert tables["JN_EMPLOYEE"] + tables["JN_CONTRACTOR"] == tables["JN_PERSON"], name
        assert entry["records"] == categories + (products + customers + per_customer * customers) * scale + (
            2 * persons * scale
        )

    generator = (java / "DataGenerator.java").read_text(encoding="utf-8")
    seed = int(re.search(r"DEFAULT_SEED = ([\d_]+)L", generator)[1].replace("_", ""))
    assert data["seed"] == seed
    test = (
        REPO
        / "bench-data"
        / "src"
        / "test"
        / "java"
        / "io"
        / "github"
        / "rzo1"
        / "jpabench"
        / "data"
        / "DatasetLoaderTest.java"
    ).read_text(encoding="utf-8")
    assert f'"{data["sizes"]["S"]["checksum"]}"' in test

    for db in ("h2", "postgresql"):
        ddl = (REPO / "bench-data" / "src" / "main" / "resources" / "ddl" / f"{db}.sql").read_text(
            encoding="utf-8"
        )
        tables = set(re.findall(r"CREATE TABLE (\w+)", ddl))
        assert tables == set(data["sizes"]["S"]["tables"]), db

    model = REPO / "bench-model" / "src" / "main" / "java" / "io" / "github" / "rzo1" / "jpabench" / "model"
    for entity in data["entities"]:
        for name in entity["entity"].split(", "):
            assert (model / f"{name}.java").is_file(), name
