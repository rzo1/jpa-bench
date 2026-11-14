# jpa-bench results

Published runs of [jpa-bench](https://github.com/rzo1/jpa-bench), rendered to
https://rzo1.github.io/jpa-bench/ by `.github/workflows/pages.yml` on every push to this branch.

```
runs/<yyyy-mm-dd>-<machine>-<contributor>/   one run.sh results directory, logs gzipped, no report/
```

## Contributing a run

On a checkout of `main`, on AC power, with nothing else running:

```bash
./run.sh --contributor <github-handle> --machine "<short machine label>"   # full run, takes hours
cd bench-viz
uv run jpa-bench-viz add-run ../results/<timestamp> <results-branch-checkout>/runs
uv run jpa-bench-viz validate --submission <results-branch-checkout>/runs/<run-id>
```

Then open a pull request against `results` that adds only `runs/<run-id>/`. CI validates the run and builds the
site; the merge publishes it. Smoke and reduced runs are accepted but listed as not comparable.

Validation fails on: missing metadata or contributor, empty or broken result files, failed or missing jobs,
verification mismatches between providers, dataset checksums that differ from the reference, uncompressed logs,
a `report/` directory, more than 50 MB.

## Building the site locally

```bash
cd <main-checkout>/bench-viz
uv run jpa-bench-viz site <results-branch-checkout>/runs -o /tmp/site
open /tmp/site/index.html
```
