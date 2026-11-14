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
"""Command line: ``jpa-bench-viz <results-dir>`` (one report), ``site``, ``validate`` and ``add-run``."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from jpa_bench_viz.addrun import add_run
from jpa_bench_viz.report import build_report
from jpa_bench_viz.results import load
from jpa_bench_viz.runinfo import DEFAULT_REPO_URL
from jpa_bench_viz.site import build_site, run_dirs
from jpa_bench_viz.validate import validate

COMMANDS = ("report", "site", "validate", "add-run")


def _format(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--format",
        choices=("svg", "png"),
        default="svg",
        help="chart format, inlined into the HTML (default: svg)",
    )


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="jpa-bench-viz", description="Reports and site for jpa-bench results."
    )
    commands = parser.add_subparsers(dest="command", required=True)

    report = commands.add_parser("report", help="report of one results/<timestamp> directory (the default)")
    report.add_argument("results_dir", type=Path)
    report.add_argument("-o", "--out", type=Path, help="output directory (default: <results-dir>/report)")
    _format(report)

    site = commands.add_parser("site", help="static site of runs/<run-id>/ directories")
    site.add_argument("runs_dir", type=Path)
    site.add_argument("-o", "--out", type=Path, required=True, help="site directory")
    site.add_argument(
        "--repo-url", default=DEFAULT_REPO_URL, help=f"jpa-bench repository (default: {DEFAULT_REPO_URL})"
    )
    _format(site)

    check = commands.add_parser("validate", help="check run directories; exit 1 on errors")
    check.add_argument("dirs", type=Path, nargs="+", help="run directories, or directories of runs")
    check.add_argument("--submission", action="store_true", help="also the checks of the results branch")

    add = commands.add_parser("add-run", help="copy a run into <runs-dir>/<run-id>/ with gzipped logs")
    add.add_argument("results_dir", type=Path)
    add.add_argument("runs_dir", type=Path)
    add.add_argument("--contributor", help="GitHub handle, if run.sh had no --contributor")
    add.add_argument("--machine", help="machine label, overrides run.sh --machine")
    add.add_argument("--name", help="run id (default: <yyyy-mm-dd>-<machine>-<contributor>)")

    argv = list(argv)
    if argv and argv[0] not in COMMANDS and argv[0] not in ("-h", "--help"):
        argv.insert(0, "report")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        return {"report": _report, "site": _site, "validate": _validate, "add-run": _add_run}[args.command](
            args
        )
    except (FileNotFoundError, ValueError) as e:
        print(f"jpa-bench-viz: {e}", file=sys.stderr)
        return 2


def _report(args: argparse.Namespace) -> int:
    results = load(args.results_dir)
    if results.scores.empty:
        print(f"jpa-bench-viz: no JMH results (*.json) in {args.results_dir}", file=sys.stderr)
        return 1
    out = args.out if args.out is not None else args.results_dir / "report"
    report = build_report(results, out, args.format)
    print(f"{len(results.scores)} results of {len(results.runners)} runners -> {report}")
    return 0


def _site(args: argparse.Namespace) -> int:
    if not args.runs_dir.is_dir():
        raise FileNotFoundError(f"not a directory: {args.runs_dir}")
    runs = build_site(args.runs_dir, args.out, args.format, args.repo_url)
    print(f"{len(runs)} runs -> {args.out / 'index.html'}")
    return 0


def _validate(args: argparse.Namespace) -> int:
    dirs: list[Path] = []
    for path in args.dirs:
        if not path.is_dir():
            raise FileNotFoundError(f"not a directory: {path}")
        dirs.extend([path] if (path / "metadata.json").is_file() or not run_dirs(path) else run_dirs(path))
    failed = 0
    for directory in dirs:
        report = validate(directory, args.submission)
        print(f"{'ok' if report.ok else 'FAILED'}: {directory}")
        for error in report.errors:
            print(f"  error: {error}")
        for warning in report.warnings:
            print(f"  warning: {warning}")
        failed += not report.ok
    return 1 if failed else 0


def _add_run(args: argparse.Namespace) -> int:
    target = add_run(args.results_dir, args.runs_dir, args.contributor, args.machine, args.name)
    print(target)
    return 0
