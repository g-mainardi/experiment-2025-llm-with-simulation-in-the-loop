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

# Push the branch head straight to the base: the server takes only a fast-forward, and no local switch to the
# base is needed (it is checked out in the main worktree).
git push origin "$branch:$base"
echo "Merged $branch into $base. GitHub deletes the remote branch; clean up with scripts/remove-worktree.sh $branch"
