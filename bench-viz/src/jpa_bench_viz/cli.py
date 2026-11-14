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
"""Command line entry point ``jpa-bench-viz <results-dir>``."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from jpa_bench_viz.report import build_report
from jpa_bench_viz.results import load


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="jpa-bench-viz",
        description="Charts and a static HTML report for a results/<timestamp>/ directory written by run.sh.",
    )
    parser.add_argument("results_dir", type=Path, help="results/<timestamp> directory")
    parser.add_argument(
        "-o",
        "--out",
        type=Path,
        default=None,
        help="output directory (default: <results-dir>/report)",
    )
    parser.add_argument(
        "--format",
        choices=("svg", "png"),
        default="svg",
        help="chart format; SVG is inlined into index.html, PNG embedded as data URI (default: svg)",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        results = load(args.results_dir)
    except FileNotFoundError as e:
        print(f"jpa-bench-viz: {e}", file=sys.stderr)
        return 2
    if results.scores.empty:
        print(f"jpa-bench-viz: no JMH results (*.json) in {args.results_dir}", file=sys.stderr)
        return 1
    out = args.out if args.out is not None else args.results_dir / "report"
    report = build_report(results, out, args.format)
    print(
        f"{len(results.scores)} results of {len(results.runners)} runners -> {report}",
    )
    return 0
