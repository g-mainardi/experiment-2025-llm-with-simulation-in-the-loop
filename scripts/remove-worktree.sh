#!/usr/bin/env bash
# Remove the worktree of a task branch and delete the branch, locally and on origin.
# Asks for confirmation if the branch has commits not in origin/master; stops first if open PRs use it.
# Usage: scripts/remove-worktree.sh <type>/<task>   e.g. scripts/remove-worktree.sh feat/self-correction
set -euo pipefail

branch=${1:-}
[[ $branch == ?*/?* ]] || { echo "usage: $0 <type>/<task>   e.g. $0 feat/self-correction" >&2; exit 1; }

warn() { echo "warning: $*" >&2; }
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
has() { git -C "$repo" rev-parse -q --verify "$1" >/dev/null; }

git -C "$repo" fetch --prune origin
local_ref=refs/heads/$branch remote_ref=refs/remotes/origin/$branch
dir=$(git -C "$repo" worktree list --porcelain |
  awk -v b="branch $local_ref" '/^worktree /{w=substr($0, 10)} $0 == b {print w}')

# Report what exists and whether it is merged into origin/master.
unmerged=0
for ref in "$local_ref" "$remote_ref"; do
  name=${ref#refs/remotes/} name=${name/#refs\/heads\//local }
  if ! has "$ref"; then
    warn "$name not found: nothing to delete"
  elif git -C "$repo" merge-base --is-ancestor "$ref" origin/master; then
    warn "$name is merged into origin/master"
  else
    warn "$name has $(git -C "$repo" rev-list --count "origin/master..$ref") commit(s) not in origin/master"
    unmerged=1
  fi
done
[[ -n $dir ]] && warn "worktree of $branch: $dir" || warn "no worktree has $branch checked out"

# A dry run triggers the pre-push hook, which refuses if open PRs use the branch: stop before touching anything.
if has "$remote_ref" && ! git -C "$repo" push --dry-run -q origin --delete "$branch"; then
  echo "Aborted: nothing was removed." >&2; exit 1
fi

if ((unmerged)); then
  read -rp "Those commits will be lost. Delete anyway? [y/N] " answer || true
  [[ $answer == [yY] ]] || { echo "Aborted." >&2; exit 1; }
fi

# git refuses to remove a worktree with uncommitted or untracked files: commit, stash or delete them first.
[[ -n $dir ]] && git -C "$repo" worktree remove "$dir"
has "$local_ref" && git -C "$repo" branch -D "$branch"
has "$remote_ref" && git -C "$repo" push origin --delete "$branch"
echo "Done."
