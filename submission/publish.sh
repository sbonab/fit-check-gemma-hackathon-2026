#!/bin/bash
set -euo pipefail
app_root="$(cd "$(dirname "$0")/.." && pwd)"
gh_bin="${GH_BIN:-gh}"
if ! command -v "$gh_bin" >/dev/null 2>&1 && [[ -x /opt/homebrew/bin/gh ]]; then
  gh_bin=/opt/homebrew/bin/gh
fi
"$gh_bin" auth status
python3 "$app_root/submission/package_source.py"
repo_dir="$app_root/tmp/submission-repo"
"$gh_bin" repo create fit-check-gemma-hackathon-2026 --public \
  --description 'On-device outfit roasts and styling advice with Gemma 4 + LiteRT-LM. Mobile AI hackathon prototype.' \
  --source "$repo_dir" --remote origin --push
"$gh_bin" repo view "$(git -C "$repo_dir" remote get-url origin)" --json url --jq .url
