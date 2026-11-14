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
"""Copies a finished run into ``runs/<run-id>/`` of the results branch."""

from __future__ import annotations

import gzip
import json
import re
import shutil
from pathlib import Path
from typing import Any

from jpa_bench_viz.results import load_metadata
from jpa_bench_viz.runinfo import machine_label

# not published: generated report, uncompressed logs (gzipped instead)
SKIPPED = ("report",)


def slug(text: str) -> str:
    text = re.sub(r"\bapple\b", "", text.lower())
    return re.sub(r"[^a-z0-9]+", "-", text).strip("-")


def run_id(metadata: dict[str, Any]) -> str:
    """``<yyyy-mm-dd>-<machine>-<contributor>``, e.g. ``2026-11-14-macbook-pro-m4-max-rzo1``."""
    stamp = str(metadata.get("timestamp", ""))
    date = f"{stamp[0:4]}-{stamp[4:6]}-{stamp[6:8]}" if re.match(r"^\d{8}T", stamp) else "0000-00-00"
    parts = [date, slug(machine_label(metadata)), slug(str(metadata.get("contributor", "")))]
    return "-".join(p for p in parts if p)


def add_run(
    results_dir: Path,
    runs_dir: Path,
    contributor: str | None = None,
    machine: str | None = None,
    name: str | None = None,
) -> Path:
    """Copies ``results_dir`` to ``runs_dir/<run-id>`` with gzipped logs; returns the new directory."""
    metadata = load_metadata(results_dir)
    if not metadata:
        raise FileNotFoundError(f"no metadata.json in {results_dir}")
    if contributor:
        metadata["contributor"] = contributor
    if machine:
        metadata.setdefault("machine", {})["label"] = machine
    if not metadata.get("contributor"):
        raise ValueError("contributor not set: pass --contributor <github handle>")
    target = runs_dir / (name or run_id(metadata))
    base, n = target, 2
    while target.exists():
        target = base.with_name(f"{base.name}-{n}")
        n += 1
    shutil.copytree(results_dir, target, ignore=shutil.ignore_patterns(*SKIPPED, "*.log"))
    for log in sorted((results_dir / "logs").glob("*.log")):
        with (
            log.open("rb") as src,
            (target / "logs" / f"{log.name}.gz").open("wb") as raw,
            gzip.GzipFile(filename="", fileobj=raw, mode="wb", compresslevel=9, mtime=0) as dst,
        ):
            shutil.copyfileobj(src, dst)
    (target / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    return target
