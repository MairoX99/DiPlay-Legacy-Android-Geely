#!/bin/sh
# Put the tree checked out here onto the public line as one commit.
#
# The public line is a fork's main branch, and a private branch's history is not what belongs there:
# merging one would publish every commit it holds, including any the tree no longer shows. So main
# gets a commit whose content is this tree and none of how it got there — an ordinary release
# snapshot.
#
# The same local deny list the pre-push hook uses is checked first, so a tree that should not be
# published is refused here rather than at the push.
#
# Usage: scripts/publish-release.sh <branch> <commit message>
#
# Run from a clean worktree with the tree you mean to publish checked out.

set -eu

target=${1:?usage: publish-release.sh <branch> <message>}
message=${2:?usage: publish-release.sh <branch> <message>}

if [ -n "$(git status --porcelain)" ]; then
  echo "publish-release: the worktree has changes; commit or set them aside first" >&2
  exit 1
fi

deny="$(git rev-parse --git-common-dir)/hooks/deny-push-paths"
if [ -f "$deny" ]; then
  leaks=$(git ls-files | grep -E -f "$deny" || true)
  if [ -n "$leaks" ]; then
    echo "publish-release: this tree holds paths the deny list names:" >&2
    echo "$leaks" | sed 's/^/  /' >&2
    exit 1
  fi
fi

tree=$(git rev-parse 'HEAD^{tree}')
git checkout -q "$target"
git read-tree -u --reset "$tree"
git commit -q -m "$message"

echo "publish-release: $target is now $(git rev-parse --short HEAD) — $(git log -1 --format=%s)"
