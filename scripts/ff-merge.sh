#!/usr/bin/env bash
# Rebase the PR branch on its base and fast-forward the base onto it, keeping the signatures.
# Usage: scripts/ff-merge.sh [branch]
set -euo pipefail
branch=${1:-$(git branch --show-current)}
base=$(gh pr view "$branch" --json baseRefName -q .baseRefName)

git fetch origin
git switch "$branch"
git rebase "origin/$base"
if [ "$(git rev-parse HEAD)" != "$(git rev-parse "origin/$branch")" ]; then
  git push --force-with-lease
  echo "Rebased onto $base: wait for CI, then run again."; exit 1
fi

gh pr checks "$branch"
# No approvals are required here: CLEAN means the success check passed and every thread is resolved.
state=$(gh pr view "$branch" --json mergeStateStatus -q .mergeStateStatus)
[ "$state" = CLEAN ] || { echo "PR not mergeable yet ($state)."; exit 1; }

git switch "$base"
git merge --ff-only "origin/$base"
git merge --ff-only "$branch"
git push origin "$base"

# GitHub deletes the remote branch on merge, maybe not yet: drop the tracking ref without waiting for it.
git branch -d "$branch"
git branch -dr "origin/$branch"
echo "Merged $branch into $base."
