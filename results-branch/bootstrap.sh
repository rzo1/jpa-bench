#!/usr/bin/env bash
#
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
#
# Creates the orphan branch "results" from this template in a new worktree. Does not push.
#
#   results-branch/bootstrap.sh [WORKTREE_DIR]    (default: ../jpa-bench-results)

set -euo pipefail

TEMPLATE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$TEMPLATE/.." && pwd)"
TARGET="${1:-$REPO/../jpa-bench-results}"

if git -C "$REPO" rev-parse --verify --quiet refs/heads/results > /dev/null; then
    echo "Branch results exists already." >&2
    exit 1
fi

git -C "$REPO" worktree add --detach "$TARGET"
cd "$TARGET"
git checkout -q --orphan results
git rm -rf -q .
git clean -fdq
(cd "$TEMPLATE" && tar cf - --exclude bootstrap.sh .) | tar xf -
git add -A
git commit -q -m "Results branch: Pages workflow, contributor README, PR template"
echo "Created branch results in $TARGET. Next:"
echo "  git -C $TARGET push -u origin results"
echo "  GitHub: Settings > Pages > Source: GitHub Actions; Environments > github-pages: allow branch results"
