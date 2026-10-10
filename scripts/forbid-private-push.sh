#!/bin/sh
# Private branches never leave this machine, and neither does anything a local deny list names.
#
# git calls this with the refs on stdin.
#
# The deny list is read from <git-common-dir>/hooks/deny-push-paths when there is one: one extended
# regular expression per line, matched against every path the pushed commits touch. It stays out of
# the repository because the paths worth denying are the reason this tree is shaped the way it is,
# and listing them in a tracked file would give away exactly what the shape is hiding.
#
# Every commit in the range is listed rather than the two endpoint trees. A commit that takes a
# denied path back out leaves no trace of it in the tree, so comparing trees alone would wave through
# a push that published all of that path's history.

set -u

deny="$(git rev-parse --git-common-dir 2>/dev/null)/hooks/deny-push-paths"
[ -f "$deny" ] || deny=""

refused=0
checked=0

while read local_ref local_sha remote_ref remote_sha
do
  [ -n "$local_ref" ] || continue
  checked=1

  case "$local_ref" in
    refs/heads/private/*)
      echo "refusing to push $local_ref: private branches stay on this machine" >&2
      refused=1
      continue
      ;;
  esac

  [ -n "$deny" ] || continue

  case "$remote_sha" in
    0000000000000000000000000000000000000000|"")
      # Nothing on the other side yet, so the whole history is new to it.
      range=$local_sha
      ;;
    *)
      range="$remote_sha..$local_sha"
      ;;
  esac

  touched=$(git log --name-only -m --format= "$range" 2>/dev/null | grep -E -f "$deny" | sort -u)

  if [ -n "$touched" ]; then
    echo "refusing to push $local_ref: its commits have held denied paths" >&2
    echo "$touched" | sed 's/^/  /' >&2
    refused=1
  fi
done

if [ "$checked" = 0 ]; then
  echo "forbid-private-push: no refs on stdin; git runs this as a pre-push hook" >&2
fi

[ "$refused" = 0 ] || exit 1
exit 0
