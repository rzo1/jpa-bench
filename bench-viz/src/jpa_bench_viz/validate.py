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
"""Checks of a run directory before it is published."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from jpa_bench_viz.results import load
from jpa_bench_viz.runinfo import summarize

RUN_ID = re.compile(r"^\d{4}-\d{2}-\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*$")
MAX_SUBMISSION_BYTES = 50 * 1024 * 1024

REQUIRED_FIELDS: tuple[tuple[str, ...], ...] = (
    ("timestamp",),
    ("git", "commit"),
    ("java", "version"),
    ("os", "name"),
    ("cpu", "model"),
    ("matrix", "runners"),
    ("matrix", "dbs"),
    ("matrix", "datasets"),
    ("jmh", "forks"),
    ("jmh", "iterations"),
    ("runners",),
)


@dataclass
class Report:
    directory: Path
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def validate(directory: Path, submission: bool = False) -> Report:
    """Errors make a run unpublishable; ``submission`` adds the checks of the results branch."""
    report = Report(directory)
    path = directory / "metadata.json"
    if not path.is_file():
        report.errors.append("metadata.json missing")
        return report
    try:
        metadata = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        report.errors.append(f"metadata.json does not parse: {e}")
        return report

    for keys in REQUIRED_FIELDS:
        value = metadata
        for key in keys:
            value = value.get(key) if isinstance(value, dict) else None
        if value in (None, "", {}, "unknown"):
            report.errors.append(f"metadata.json: {'.'.join(keys)} not set")
    if not metadata.get("contributor"):
        report.errors.append(
            "metadata.json: contributor not set (run.sh --contributor or add-run --contributor)"
        )
    if "machine" not in metadata:
        report.warnings.append("metadata.json: no machine section (run.sh before machine recording)")

    for result in sorted(directory.glob("*.json")) + sorted((directory / "metrics").glob("*.json")):
        if result.name == "metadata.json":
            continue
        text = result.read_text(encoding="utf-8").strip()
        if not text:
            report.errors.append(f"{result.relative_to(directory)}: empty (aborted or running JMH run)")
            continue
        try:
            if not isinstance(json.loads(text), list):
                report.errors.append(f"{result.relative_to(directory)}: not a JMH result list")
        except json.JSONDecodeError as e:
            report.errors.append(f"{result.relative_to(directory)}: does not parse: {e}")
    if load(directory).scores.empty:
        report.errors.append("no JMH results")

    info = summarize(directory)
    if not (directory / "execution.tsv").is_file():
        report.errors.append("execution.tsv missing")
    elif not info.jobs_complete:
        expected = f" of {info.jobs_expected}" if info.jobs_expected else ""
        report.errors.append(f"incomplete run: {info.jobs_ok}{expected} jobs ok, {info.jobs_failed} failed")
    if info.mismatches is None:
        report.errors.append("verification.tsv missing or empty")
    elif info.mismatches:
        report.errors.append(
            f"{info.mismatches} verification mismatch(es): providers return different results"
        )
    for dataset in info.loaded:
        if dataset.matches is False:
            report.errors.append(
                f"dataset {dataset.size}: checksum {dataset.checksum} is not {dataset.expected['checksum']}"
            )
        elif dataset.matches is None:
            report.warnings.append(f"dataset {dataset.size}: seed {dataset.seed} is not the default seed")
    if len({d.size for d in info.loaded}) < len(info.loaded):
        report.errors.append("runners loaded different data for the same dataset")
    if not info.loaded:
        report.warnings.append("no dataset checksums (datasets.tsv or logs) found")
    if metadata.get("git", {}).get("dirty"):
        report.warnings.append("built from a dirty working tree")
    if info.kind != "full":
        report.warnings.append(f"{info.kind} run: published but marked as not comparable")

    if submission:
        if not RUN_ID.match(directory.name):
            report.errors.append(
                f"directory name {directory.name!r} is not <yyyy-mm-dd>-<machine>-<contributor>"
            )
        if (directory / "report").exists():
            report.errors.append("report/ must not be submitted (the site is generated)")
        if any((directory / "logs").glob("*.log")):
            report.errors.append("logs must be gzipped (jpa-bench-viz add-run does that)")
        size = sum(p.stat().st_size for p in directory.rglob("*") if p.is_file())
        if size > MAX_SUBMISSION_BYTES:
            report.errors.append(f"{size / 1024**2:.0f} MB, more than {MAX_SUBMISSION_BYTES // 1024**2} MB")
    return report
