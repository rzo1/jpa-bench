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
"""Data model and dataset sizes (``datasets.json``) and the datasets a run loaded."""

from __future__ import annotations

import gzip
import json
import re
from dataclasses import dataclass
from functools import cache
from importlib import resources
from pathlib import Path
from typing import Any

# keys of the "# jpa-bench: dataset" log line -> tables
COUNT_TABLES: dict[str, str] = {
    "categories": "CATEGORY",
    "products": "PRODUCT",
    "customers": "CUSTOMER",
    "orders": "ORDERS",
    "orderLines": "ORDER_LINE",
    "stPersons": "ST_PERSON",
    "jnPersons": "JN_PERSON",
}

_LOG_LINE = re.compile(
    r"^# jpa-bench: dataset (?P<size>\S+) seed=(?P<seed>\S+) checksum=(?P<checksum>\S+) "
    r"records=(?P<records>\S+) \((?P<counts>.*)\) loaded"
)
_LOG_NAME = re.compile(r"^(?:metrics-)?(?P<runner>.+)-(?P<db>[^-]+)-(?P<dataset>[^-]+)\.log(?:\.gz)?$")


@cache
def reference() -> dict[str, Any]:
    """The checked-in ``datasets.json``."""
    text = resources.files("jpa_bench_viz").joinpath("datasets.json").read_text(encoding="utf-8")
    return json.loads(text)


@dataclass(frozen=True)
class LoadedDataset:
    """One dataset as a run loaded it (identical for every runner and database, or not)."""

    size: str
    seed: str
    checksum: str
    records: str
    counts: dict[str, str]
    sources: tuple[str, ...]

    @property
    def expected(self) -> dict[str, Any] | None:
        ref = reference()
        if self.seed != str(ref["seed"]):
            return None
        return ref["sizes"].get(self.size)

    @property
    def matches(self) -> bool | None:
        """True if seed and checksum equal the reference, None for another seed."""
        expected = self.expected
        if expected is None:
            return None
        return self.checksum == expected["checksum"]


def _parse_counts(text: str) -> dict[str, str]:
    counts = {}
    for part in text.split(","):
        key, _, value = part.strip().partition("=")
        if key:
            counts[key] = value
    return counts


def _rows(directory: Path) -> list[dict[str, str]]:
    """``datasets.tsv``, or the dataset lines of the logs of runs without it."""
    path = directory / "datasets.tsv"
    if path.is_file():
        lines = path.read_text(encoding="utf-8").splitlines()
        if not lines:
            return []
        header = lines[0].split("\t")
        return [dict(zip(header, line.split("\t"), strict=False)) for line in lines[1:] if line.strip()]
    rows = []
    for log in sorted((directory / "logs").glob("*.log*")):
        name = _LOG_NAME.match(log.name)
        if name is None:
            continue
        opener = gzip.open if log.suffix == ".gz" else open
        with opener(log, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                match = _LOG_LINE.match(line)
                if match:
                    rows.append(
                        {"runner": name["runner"], "db": name["db"], "dataset": match["size"]}
                        | {k: match[k] for k in ("seed", "checksum", "records", "counts")}
                    )
    return rows


def loaded(directory: Path) -> list[LoadedDataset]:
    """Distinct (size, seed, checksum, counts) the run loaded; more than one per size is a mismatch."""
    groups: dict[tuple[str, str, str, str, str], list[str]] = {}
    for row in _rows(directory):
        key = (
            row.get("dataset", ""),
            row.get("seed", ""),
            row.get("checksum", ""),
            row.get("records", ""),
            row.get("counts", ""),
        )
        groups.setdefault(key, []).append(f"{row.get('runner', '')} {row.get('db', '')}")
    order = list(reference()["sizes"])
    result = [
        LoadedDataset(size, seed, checksum, records, _parse_counts(counts), tuple(sorted(set(sources))))
        for (size, seed, checksum, records, counts), sources in groups.items()
    ]
    result.sort(key=lambda d: (order.index(d.size) if d.size in order else len(order), d.size, d.checksum))
    return result
