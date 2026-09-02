#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?}"
: "${GITHUB_SHA:?}"
: "${RELEASE_TAG:?}"
if [[ ! "${RELEASE_TAG}" =~ ^9\.3\.0-[A-Za-z0-9]+$ ]] ||
   [[ ! "${GITHUB_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid release tag or commit SHA." >&2
  exit 1
fi

artifacts_dir="${1:-artifacts}"
release_api="repos/${GITHUB_REPOSITORY}/releases"
temporary_dir="$(mktemp -d)"
trap 'rm -rf "${temporary_dir}"' EXIT
if command -v sha256sum > /dev/null; then
  checksum=(sha256sum)
else
  checksum=(shasum -a 256)
fi

assets=()
for artifact_os in linux darwin; do
  for artifact_arch in x86_64 arm64; do
    asset_name="bazel-${RELEASE_TAG}-${artifact_os}-${artifact_arch}"
    for suffix in '' .sha256 .intoto.jsonl; do
      asset="${artifacts_dir}/${asset_name}${suffix}"
      test -s "${asset}"
      assets+=("${asset}")
    done
    (cd "${artifacts_dir}" && "${checksum[@]}" --check "${asset_name}.sha256")
  done
done
for asset in "${assets[@]}"; do
  digest="$("${checksum[@]}" "${asset}")"
  jq --null-input --compact-output \
    --arg name "${asset##*/}" \
    --argjson size "$(wc -c < "${asset}")" \
    --arg digest "sha256:${digest%% *}" \
    '{name: $name, size: $size, digest: $digest, state: "uploaded"}'
done | jq --slurp 'sort_by(.name)' > "${temporary_dir}/assets.json"

# Matching refs returns an empty array for an absent tag; API errors must fail.
existing_tags="$(gh api "repos/${GITHUB_REPOSITORY}/git/matching-refs/tags/${RELEASE_TAG}" \
  --jq ".[] | select(.ref == \"refs/tags/${RELEASE_TAG}\") | .ref")"
existing_releases="$(gh api --paginate "${release_api}?per_page=100" \
  --jq ".[] | select(.tag_name == \"${RELEASE_TAG}\") | .id")"
if [[ -n "${existing_tags}" || -n "${existing_releases}" ]]; then
  echo "Release or tag ${RELEASE_TAG} already exists; refusing to replace it." >&2
  exit 1
fi

gh api --method POST "${release_api}" \
  -f "tag_name=${RELEASE_TAG}" \
  -f "target_commitish=${GITHUB_SHA}" \
  -f "name=${RELEASE_TAG}" \
  -f "body=Bazel 9.3 binaries for Linux and macOS. Use USE_BAZEL_VERSION=dzbarsky/${RELEASE_TAG} with Bazelisk." \
  -F draft=true > "${temporary_dir}/created.json"
release_id="$(jq --exit-status --raw-output \
  '.id | select(type == "number" and . > 0 and . == floor)' \
  "${temporary_dir}/created.json")"
release_api="${release_api}/${release_id}"

gh release upload "${RELEASE_TAG}" "${assets[@]}" --repo "${GITHUB_REPOSITORY}"
gh api "${release_api}" > "${temporary_dir}/draft.json"
jq --exit-status \
  --arg tag "${RELEASE_TAG}" --arg sha "${GITHUB_SHA}" \
  --slurpfile expected "${temporary_dir}/assets.json" \
  '.draft == true and .tag_name == $tag and .target_commitish == $sha and
   (.assets | map({name, size, digest, state}) | sort_by(.name)) == $expected[0]' \
  "${temporary_dir}/draft.json" > /dev/null

# Enable repository release immutability before running this workflow.
gh api --method PATCH "${release_api}" -F draft=false > /dev/null
gh api "${release_api}" > "${temporary_dir}/published.json"
jq --exit-status \
  --arg tag "${RELEASE_TAG}" --arg sha "${GITHUB_SHA}" \
  --slurpfile expected "${temporary_dir}/assets.json" \
  '.draft == false and .immutable == true and .tag_name == $tag and
   .target_commitish == $sha and
   (.assets | map({name, size, digest, state}) | sort_by(.name)) == $expected[0]' \
  "${temporary_dir}/published.json" > /dev/null
gh api "repos/${GITHUB_REPOSITORY}/git/ref/tags/${RELEASE_TAG}" \
  > "${temporary_dir}/tag.json"
jq --exit-status --arg sha "${GITHUB_SHA}" \
  '.object.type == "commit" and .object.sha == $sha' \
  "${temporary_dir}/tag.json" > /dev/null
printf 'Published immutable release %s at %s.\n' "${RELEASE_TAG}" "${GITHUB_SHA}"
