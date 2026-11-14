<!-- One run per pull request, added with `jpa-bench-viz add-run`. -->

Run: `runs/<yyyy-mm-dd>-<machine>-<contributor>/`

- [ ] full run (`./run.sh --contributor <handle>`, no `--smoke`, default JMH settings)
- [ ] built from a clean checkout of `main` (`git.dirty` is false)
- [ ] on AC power, Low Power Mode off, no other load
- [ ] `uv run jpa-bench-viz validate --submission runs/<run-id>` passes

Machine and anything unusual (thermal throttling, VM, container limits):
