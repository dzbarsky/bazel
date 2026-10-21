#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REF_NAME:?}"
if [[ ! "${GITHUB_REF_NAME}" =~ ^release-(([1-9][0-9]*)\.0\.0-pre\.([0-9]{8})\.([1-9][0-9]*))rc1$ ]]; then
  echo "Release branch must be release-MAJOR.0.0-pre.YYYYMMDD.Nrc1: ${GITHUB_REF_NAME}" >&2
  exit 1
fi
branch_tag="${BASH_REMATCH[1]}"
branch_version="${BASH_REMATCH[2]}.0.0"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
bazel_version="$(sed -n '/^module(/,/^)/{
  s/^[[:space:]]*version[[:space:]]*=[[:space:]]*"\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)[^"]*".*/\1/p
}' "${root}/MODULE.bazel")"
if [[ "${bazel_version}" != "${branch_version}" ]]; then
  echo "Release branch version ${branch_version} must match MODULE.bazel version MAJOR.0.0 (found ${bazel_version})." >&2
  exit 1
fi

case "${1:-}" in
  validate)
    release_tag="${2:?}"
    ;;
  select)
    : "${GITHUB_OUTPUT:?}"
    release_tag="${INPUT_RELEASE_TAG:-${branch_tag}}"
    ;;
  *)
    echo "Usage: $0 select | validate RELEASE_TAG" >&2
    exit 1
    ;;
esac

if [[ "${release_tag}" != "${branch_tag}" ]]; then
  echo "Invalid release tag for ${GITHUB_REF_NAME}: ${release_tag}; expected ${branch_tag}." >&2
  exit 1
fi
if [[ "$1" == select ]]; then
  echo "release_tag=${release_tag}" >> "${GITHUB_OUTPUT}"
fi
