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
"""Summary of one run: when, where, what and whether it is comparable."""

from __future__ import annotations

import csv
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from jpa_bench_viz.datasets import LoadedDataset, loaded
from jpa_bench_viz.results import (
    DB_LABELS,
    KNOWN_DBS,
    load_metadata,
    load_verification,
    provider_label,
    runner_order,
    verification_mismatches,
)

OPENJPA_COMMIT_URL = "https://github.com/apache/openjpa/commit/"
DEFAULT_REPO_URL = "https://github.com/rzo1/jpa-bench"


@dataclass
class Provider:
    runner: str
    label: str
    name: str
    version: str
    enhancement: str
    build: str
    revision: str

    @property
    def revision_url(self) -> str:
        if self.revision and "openjpa" in self.name.lower():
            return OPENJPA_COMMIT_URL + self.revision
        return ""


@dataclass
class RunInfo:
    directory: Path
    metadata: dict[str, Any]
    started: datetime | None
    finished: datetime | None
    kind: str
    providers: list[Provider]
    dbs: list[str]
    datasets: list[str]
    loaded: list[LoadedDataset]
    jobs_ok: int
    jobs_failed: int
    jobs_expected: int | None
    mismatches: int | None
    notes: list[str] = field(default_factory=list)

    @property
    def run_id(self) -> str:
        return self.directory.name

    @property
    def comparable(self) -> bool:
        return self.kind == "full"

    @property
    def date(self) -> str:
        return self.started.strftime("%Y-%m-%d %H:%M UTC") if self.started else ""

    @property
    def duration(self) -> str:
        if not self.started or not self.finished:
            return ""
        minutes = int((self.finished - self.started).total_seconds() // 60)
        return f"{minutes // 60} h {minutes % 60:02d} min" if minutes >= 60 else f"{minutes} min"

    @property
    def contributor(self) -> str:
        return str(self.metadata.get("contributor") or "")

    @property
    def machine(self) -> str:
        return machine_label(self.metadata)

    @property
    def hardware(self) -> str:
        return hardware(self.metadata)

    @property
    def os(self) -> str:
        os_ = self.metadata.get("os", {})
        pretty = os_.get("pretty") or os_.get("name", "")
        kernel = f"{os_.get('name', '')} {os_.get('release', '')}".strip()
        parts = [pretty, f"kernel {kernel}" if pretty != os_.get("name") else os_.get("release", "")]
        return ", ".join(p for p in parts if p) + (f", {os_['arch']}" if os_.get("arch") else "")

    @property
    def jdk(self) -> str:
        java = self.metadata.get("java", {})
        return f"{java.get('version', '')} ({java.get('vendor', '')})" if java else ""

    @property
    def power(self) -> str:
        machine = self.metadata.get("machine", {})
        parts = [machine.get("power", "")]
        if machine.get("battery"):
            parts.append(f"battery {machine['battery']}")
        low = machine.get("lowPowerMode")
        if low is True:
            parts.append("Low Power Mode ON")
        elif low is False:
            parts.append("Low Power Mode off")
        if machine.get("governor"):
            parts.append(f"governor {machine['governor']}")
        return ", ".join(p for p in parts if p)

    @property
    def low_power(self) -> bool:
        machine = self.metadata.get("machine", {})
        return machine.get("lowPowerMode") is True or "battery" in str(machine.get("power", "")).lower()

    @property
    def jobs_complete(self) -> bool:
        return self.jobs_failed == 0 and (self.jobs_expected is None or self.jobs_ok >= self.jobs_expected)

    @property
    def db_labels(self) -> list[str]:
        return [DB_LABELS.get(db, db) for db in self.dbs]

    @property
    def h2_version(self) -> str:
        for info in self.metadata.get("runners", {}).values():
            version = info.get("harness", {}).get("bench.version.h2")
            if version:
                return version
        return ""

    @property
    def postgres(self) -> str:
        pg = self.metadata.get("postgres", {})
        if not pg:
            return ""
        if pg.get("external"):
            return f"external server {pg['external']}"
        parts = (
            pg.get("image", ""),
            f"{pg.get('cpus', '')} CPUs",
            f"{pg.get('memoryMb', '')} MB",
            pg.get("settings"),
        )
        return ", ".join(p for p in parts if p)

    def commit_url(self, repo_url: str = DEFAULT_REPO_URL) -> str:
        commit = self.metadata.get("git", {}).get("commit", "")
        return f"{repo_url}/commit/{commit}" if re.fullmatch(r"[0-9a-f]{7,40}", commit or "") else ""


def machine_label(metadata: dict[str, Any]) -> str:
    """``--machine`` label, else model and chip, e.g. ``MacBook Pro, Apple M4 Max``."""
    machine = metadata.get("machine", {})
    if machine.get("label"):
        return machine["label"]
    cpu = metadata.get("cpu", {}).get("model", "")
    model = machine.get("model", "")
    return ", ".join(p for p in (model, cpu) if p and p != "unknown") or "unknown machine"


def hardware(metadata: dict[str, Any]) -> str:
    """Model, chip, cores and memory."""
    machine = metadata.get("machine", {})
    cpu = metadata.get("cpu", {})
    model = " ".join(p for p in (machine.get("vendor", ""), machine.get("model", "")) if p)
    if machine.get("modelId"):
        model += f" ({machine['modelId']})"
    cores = f"{cpu.get('cores', '?')} cores"
    if cpu.get("coreTypes"):
        cores += f" ({cpu['coreTypes']})"
    memory = f"{(cpu.get('memoryBytes') or 0) / 1024**3:.0f} GiB"
    chip = cpu.get("model", "")
    if chip and chip in machine_label(metadata):
        chip = ""
    return ", ".join(p for p in (model, chip, cores, memory) if p)


def run_kind(metadata: dict[str, Any]) -> str:
    """``smoke``, ``reduced`` (fewer forks or iterations than the defaults) or ``full``."""
    jmh = metadata.get("jmh", {})
    if metadata.get("smoke") is True:
        return "smoke"
    forks, iterations = jmh.get("forks", 0), jmh.get("iterations", 0)
    if forks <= 1 and iterations <= 1:
        return "smoke"
    if forks < 2 or iterations < 5 or jmh.get("warmupIterations", 0) < 5:
        return "reduced"
    return "full"


def _parse_time(value: str) -> datetime | None:
    for fmt in ("%Y-%m-%dT%H:%M:%SZ", "%Y%m%dT%H%M%SZ"):
        try:
            return datetime.strptime(value, fmt).replace(tzinfo=UTC)
        except (TypeError, ValueError):
            continue
    return None


def read_execution(directory: Path) -> list[dict[str, str]]:
    path = directory / "execution.tsv"
    if not path.is_file():
        return []
    with path.open(encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f, delimiter="\t"))


def expected_jobs(metadata: dict[str, Any]) -> int | None:
    matrix = metadata.get("matrix", {})
    if not matrix:
        return None
    runners = len(str(matrix.get("runners", "")).split())
    dbs = len(str(matrix.get("dbs", "")).split())
    datasets = len(str(matrix.get("datasets", "")).split())
    per_dataset = 2 if metadata.get("metering", {}).get("mode") == "separate" else 1
    bootstrap = 2 if matrix.get("bootstrap", True) else 0
    return runners * dbs * (datasets * per_dataset + bootstrap)


def providers(metadata: dict[str, Any], runners: list[str]) -> list[Provider]:
    result = []
    for runner in runners:
        harness = metadata.get("runners", {}).get(runner, {}).get("harness", {})
        result.append(
            Provider(
                runner=runner,
                label=provider_label(metadata, runner),
                name=harness.get("bench.provider.name", ""),
                version=harness.get("bench.provider.version", ""),
                enhancement=harness.get("bench.provider.enhancement", ""),
                build=harness.get("bench.provider.build", ""),
                revision=harness.get("bench.provider.revision", ""),
            )
        )
    return result


def summarize(directory: Path) -> RunInfo:
    """Reads metadata.json, execution.tsv, verification.tsv and the loaded datasets of a run."""
    metadata = load_metadata(directory)
    matrix = metadata.get("matrix", {})
    jobs = read_execution(directory)
    starts = [t for t in (_parse_time(j.get("start", "")) for j in jobs) if t]
    ends = [t for t in (_parse_time(j.get("end", "")) for j in jobs) if t]
    started = min(starts) if starts else _parse_time(str(metadata.get("timestamp", "")))
    verification = load_verification(directory)
    runners = runner_order(set(str(matrix.get("runners", "")).split()) or set(metadata.get("runners", {})))
    dbs = str(matrix.get("dbs", "")).split()
    info = RunInfo(
        directory=directory,
        metadata=metadata,
        started=started,
        finished=max(ends) if ends else None,
        kind=run_kind(metadata),
        providers=providers(metadata, runners),
        dbs=[d for d in KNOWN_DBS if d in dbs] + [d for d in dbs if d not in KNOWN_DBS],
        datasets=str(matrix.get("datasets", "")).split(),
        loaded=loaded(directory),
        jobs_ok=sum(1 for j in jobs if j.get("status") == "ok"),
        jobs_failed=sum(1 for j in jobs if j.get("status") not in ("ok", None)),
        jobs_expected=expected_jobs(metadata),
        mismatches=None if verification.empty else len(verification_mismatches(verification)),
    )
    if info.kind == "smoke":
        info.notes.append(
            "Smoke run (1 fork, 1 iteration of 1 s): checks that everything runs. Not comparable."
        )
    elif info.kind == "reduced":
        info.notes.append(
            "Reduced JMH settings (fewer forks or iterations than the defaults). Not comparable."
        )
    if metadata.get("git", {}).get("dirty"):
        info.notes.append("Built from a working tree with uncommitted changes.")
    if info.low_power:
        info.notes.append("Ran on battery or in Low Power Mode.")
    if not info.jobs_complete:
        expected = f" of {info.jobs_expected}" if info.jobs_expected else ""
        info.notes.append(f"Incomplete: {info.jobs_ok}{expected} jobs ok, {info.jobs_failed} failed.")
    return info
