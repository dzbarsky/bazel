#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REF_NAME:?}"
if [[ ! "${GITHUB_REF_NAME}" =~ ^dzbarsky-([0-9]{2})\.([0-9]{2})\.([0-9]{4})$ ]]; then
  echo "Release branch must be dzbarsky-DD.MM.YYYY: ${GITHUB_REF_NAME}" >&2
  exit 1
fi
release_date="${BASH_REMATCH[3]}${BASH_REMATCH[2]}${BASH_REMATCH[1]}"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bazel_version="$(sed -n '/^module(/,/^)/{
  s/^[[:space:]]*version[[:space:]]*=[[:space:]]*"\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)[^"]*".*/\1/p
}' "${root}/MODULE.bazel")"
if [[ ! "${bazel_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Cannot read the Bazel version from ${root}/MODULE.bazel." >&2
  exit 1
fi
tag_prefix="${bazel_version}-dzbarsky${release_date}"

valid_release_tag() {
  local tag="$1" revision
  if [[ "${tag}" == "${tag_prefix}" ]]; then
    return 0
  fi
  if [[ "${tag}" != "${tag_prefix}"r* ]]; then
    return 1
  fi
  revision="${tag#"${tag_prefix}"r}"
  [[ "${revision}" =~ ^([2-9]|[1-9][0-9]+)$ ]]
}

case "${1:-}" in
  validate)
    release_tag="${2:?}"
    ;;
  select)
    : "${GITHUB_REPOSITORY:?}"
    : "${GITHUB_OUTPUT:?}"
    if [[ -n "${INPUT_RELEASE_TAG:-}" ]]; then
      release_tag="${INPUT_RELEASE_TAG}"
    else
      published_tags="$(gh api --paginate "repos/${GITHUB_REPOSITORY}/releases?per_page=100" --jq '.[] | select(.draft == false) | .tag_name')"
      max_release_number=0
      while IFS= read -r published_tag; do
        if ! valid_release_tag "${published_tag}"; then
          continue
        fi
        if [[ "${published_tag}" == "${tag_prefix}" ]]; then
          release_number=1
        else
          release_number="${published_tag#"${tag_prefix}"r}"
        fi
        if (( release_number > max_release_number )); then
          max_release_number="${release_number}"
        fi
      done <<< "${published_tags}"
      if (( max_release_number == 0 )); then
        release_tag="${tag_prefix}"
      else
        release_tag="${tag_prefix}r$((max_release_number + 1))"
      fi
    fi
    ;;
  *)
    echo "Usage: $0 select | validate RELEASE_TAG" >&2
    exit 1
    ;;
esac

if ! valid_release_tag "${release_tag}"; then
  echo "Invalid release tag for ${GITHUB_REF_NAME}: ${release_tag}; expected ${tag_prefix} or ${tag_prefix}r2, r3, ..." >&2
  exit 1
fi
if [[ "$1" == select ]]; then
  echo "release_tag=${release_tag}" >> "${GITHUB_OUTPUT}"
fi
