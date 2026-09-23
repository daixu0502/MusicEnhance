"""Version-aware GitHub publishing. Uses Python's standard library and Git only."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlparse
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/github-release"
PLAN = WORK / "plan.json"
NOTES = WORK / "notes.md"
DIST = WORK / "dist"
VERSION = re.compile(r"^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:\.(?:0|[1-9]\d*))?(?:-beta(?:\.?\d+)?)?$", re.I)
MAX_NOTES_BYTES = 100_000


def classify_version(version):
    if not isinstance(version, str) or not VERSION.fullmatch(version):
        raise ValueError("versionName must be numeric (2.0 / 2.0.1) or beta (2.1-beta / 2.1-beta1 / 2.1-beta.1)")
    return "-beta" in version.lower()


def validate_metadata(metadata):
    prerelease = classify_version(metadata["versionName"])
    for name in ("versionCode", "compileSdk"):
        if type(metadata[name]) is not int or metadata[name] <= 0:
            raise ValueError(f"{name} must be a positive integer")
    if not re.fullmatch(r"\d+\.\d+\.\d+", metadata["buildToolsVersion"]):
        raise ValueError("buildToolsVersion must identify a stable Android build-tools package")
    return prerelease


def git(*arguments, check=True):
    return subprocess.run(["git", *arguments], cwd=ROOT, text=True, encoding="utf-8",
                          capture_output=True, check=check)


def resolve_commit(reference):
    result = git("rev-parse", "--verify", "--end-of-options", f"{reference}^{{commit}}", check=False)
    if result.returncode:
        raise ValueError(f"Cannot resolve Git reference {reference!r}; full history and tags are required")
    return result.stdout.strip()


class GitHub:
    def __init__(self):
        self.repository = os.environ["GITHUB_REPOSITORY"]
        self.server_url = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
        self.api_url = os.environ.get("GITHUB_API_URL", "https://api.github.com")
        self.token = os.environ["GH_TOKEN"]

    def request(self, method, endpoint, payload=None, binary=None):
        url = endpoint if endpoint.startswith("https://") else f"{self.api_url}/repos/{self.repository}/{endpoint}"
        if urlparse(url).hostname not in {
            urlparse(self.api_url).hostname, urlparse(self.server_url).hostname, "uploads.github.com",
        }:
            raise ValueError("Refusing an unexpected GitHub upload host")
        data = binary if binary is not None else (json.dumps(payload).encode() if payload is not None else None)
        headers = {"Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "MusicEnhance-release-workflow"}
        if data is not None:
            headers["Content-Type"] = "application/octet-stream" if binary is not None else "application/json"
        try:
            with urlopen(Request(url, data=data, headers=headers, method=method), timeout=60) as response:
                content = response.read()
                return json.loads(content) if content else None
        except HTTPError as error:
            # Do not print request headers or credentials on failure.
            raise RuntimeError(f"GitHub API {method} failed with HTTP {error.code}; check Actions permissions and logs") from None

    def pages(self, endpoint):
        values = []
        page = 1
        while True:
            batch = self.request("GET", f"{endpoint}?per_page=100&page={page}")
            values.extend(batch)
            if len(batch) < 100:
                return values
            page += 1


def previous_release(releases, prerelease, current_tag):
    candidates = [release for release in releases
                  if not release["draft"] and release["prerelease"] == prerelease
                  and release["tag_name"] != current_tag]
    return max(candidates, key=lambda release: (release.get("published_at") or "", release["id"]), default=None)


def commit_messages(previous_tag, commit):
    revision = commit if previous_tag is None else f"{resolve_commit('refs/tags/' + previous_tag)}..{commit}"
    output = git("log", "--reverse", "--no-merges", "--format=%H%x00%s", revision, "--").stdout
    return [tuple(line.split("\0", 1)) for line in output.splitlines() if line]


def markdown_text(text):
    text = " ".join(text.split()).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return re.sub(r"([\\`*_\[\]])", r"\\\1", text)


def release_notes(repository, server_url, version, prerelease, previous_tag, commits):
    channel = "预发布版（Beta）" if prerelease else "正式版"
    repository_url = f"{server_url}/{repository}"
    tag = "v" + version
    lines = [f"## {version} · {channel}", ""]
    if previous_tag:
        lines += [f"以下为自上一个同类型版本 **{markdown_text(previous_tag)}** 以来的改动。", ""]
    else:
        lines += [f"这是首个{channel}，以下汇总截至本版本的提交。", ""]
    lines += ["### 改动记录", ""]
    shown = 0
    used_bytes = len("\n".join(lines).encode())
    for sha, subject in commits:
        item = f"- {markdown_text(subject)} ([{sha[:7]}]({repository_url}/commit/{sha}))"
        if used_bytes + len(item.encode()) > MAX_NOTES_BYTES - 2_000:
            break
        lines.append(item)
        used_bytes += len(item.encode()) + 1
        shown += 1
    if not commits:
        lines.append("- 与上一个同类型版本相比，没有新的非合并提交。")
    if shown < len(commits):
        lines += ["", f"共 {len(commits)} 项提交，此处列出前 {shown} 项；其余请查看完整对比。"]
    if previous_tag:
        comparison = f"{repository_url}/compare/{quote(previous_tag, safe='')}...{quote(tag, safe='')}"
    else:
        comparison = f"{repository_url}/commits/{quote(tag, safe='')}"
    lines += ["", f"**完整对比/历史**：{comparison}", ""]
    return "\n".join(lines)


def output(name, value):
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(f"{name}={value}\n")


def summary(text):
    print(text)
    if "GITHUB_STEP_SUMMARY" in os.environ:
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as stream:
            stream.write(text + "\n\n")


def verify_draft_target(release, commit):
    if resolve_commit(release["target_commitish"]) != commit:
        raise ValueError("An existing draft for this version targets another commit; resolve the draft before retrying")


def prepare():
    metadata = json.loads((ROOT / "app/build/release-metadata.json").read_text(encoding="utf-8"))
    prerelease = validate_metadata(metadata)
    version = metadata["versionName"]
    tag = "v" + version
    reference = os.environ.get("GITHUB_REF", "")
    if reference.startswith("refs/tags/") and reference != "refs/tags/" + tag:
        raise ValueError(f"Tag must match app versionName exactly: expected {tag}")
    api = GitHub()
    releases = api.pages("releases")
    existing = next((item for item in releases if item["tag_name"] == tag), None)
    if existing and not existing["draft"]:
        output("publish", "false")
        summary(f"{tag} 已发布，跳过构建和上传。发布新版本请同时提高 versionName 和 versionCode。")
        return
    commit = resolve_commit("HEAD")
    if existing:
        verify_draft_target(existing, commit)
    tag_result = git("show-ref", "--verify", "--quiet", "refs/tags/" + tag, check=False)
    if tag_result.returncode == 0 and resolve_commit("refs/tags/" + tag) != commit:
        raise ValueError(f"Existing tag {tag} points to another commit; refusing to replace it")
    previous = previous_release(releases, prerelease, tag)
    previous_tag = previous["tag_name"] if previous else None
    notes = release_notes(api.repository, api.server_url, version, prerelease, previous_tag,
                          commit_messages(previous_tag, commit))
    plan = {**metadata, "tag": tag, "prerelease": prerelease, "commit": commit, "previous_tag": previous_tag}
    WORK.mkdir(parents=True, exist_ok=True)
    PLAN.write_text(json.dumps(plan, indent=2), encoding="utf-8")
    NOTES.write_text(notes, encoding="utf-8")
    for name, value in {"publish": "true", "version": version, "compile_sdk": metadata["compileSdk"],
                        "build_tools": metadata["buildToolsVersion"]}.items():
        output(name, value)
    summary(f"计划发布 {tag}（{'Pre-release' if prerelease else 'Release'}）；改动基准：{previous_tag or '仓库起点'}。")


def signing():
    for name in ("MUSICENHANCE_KEYSTORE_BASE64", "MUSICENHANCE_KEY_ALIAS", "MUSICENHANCE_STORE_PASSWORD"):
        if not os.environ.get(name):
            raise ValueError(f"Missing repository Actions secret: {name}")
    encoded = "".join(os.environ["MUSICENHANCE_KEYSTORE_BASE64"].split())
    try:
        key_bytes = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise ValueError("MUSICENHANCE_KEYSTORE_BASE64 is not valid Base64") from None
    if not key_bytes:
        raise ValueError("The signing keystore is empty")
    keystore = Path(os.environ["MUSICENHANCE_KEYSTORE"])
    keystore.write_bytes(key_bytes)
    keystore.chmod(0o600)


def package():
    plan = json.loads(PLAN.read_text(encoding="utf-8"))
    apk_directory = ROOT / "app/build/outputs/apk/release"
    metadata = json.loads((apk_directory / "output-metadata.json").read_text(encoding="utf-8"))
    elements = metadata["elements"]
    if len(elements) != 1 or elements[0]["outputFile"] != "app-release.apk":
        raise ValueError("Expected one signed app-release.apk")
    if elements[0]["versionName"] != plan["versionName"] or elements[0]["versionCode"] != plan["versionCode"]:
        raise ValueError("Built APK version differs from the planned release")
    DIST.mkdir(parents=True, exist_ok=True)
    apk = DIST / f"MusicEnhance-{plan['tag']}.apk"
    shutil.copyfile(apk_directory / "app-release.apk", apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    apk.with_suffix(".apk.sha256").write_text(f"{digest}  {apk.name}\n", encoding="utf-8")


def publish_release(api, plan, notes, assets):
    existing = next((item for item in api.pages("releases") if item["tag_name"] == plan["tag"]), None)
    if existing and not existing["draft"]:
        return existing # A rerun must never replace assets/notes of an already public release.
    if existing:
        verify_draft_target(existing, plan["commit"])
    payload = {"tag_name": plan["tag"], "target_commitish": plan["commit"], "name": plan["tag"],
               "body": notes, "draft": True, "prerelease": plan["prerelease"]}
    if existing:
        release = api.request("PATCH", f"releases/{existing['id']}", payload)
    else:
        release = api.request("POST", "releases", payload)
    asset_endpoint = f"releases/{release['id']}/assets"
    uploaded = api.pages(asset_endpoint)
    for asset in assets:
        for old in uploaded:
            if old["name"] == asset.name:
                api.request("DELETE", f"releases/assets/{old['id']}")
        upload_url = release["upload_url"].split("{", 1)[0] + "?" + urlencode({"name": asset.name})
        api.request("POST", upload_url, binary=asset.read_bytes())
    # Keep a failed upload private as a recoverable draft; publish only after every asset succeeds.
    return api.request("PATCH", f"releases/{release['id']}", {
        "draft": False, "prerelease": plan["prerelease"],
        "make_latest": "false" if plan["prerelease"] else "true",
    })


def publish():
    plan = json.loads(PLAN.read_text(encoding="utf-8"))
    apk = DIST / f"MusicEnhance-{plan['tag']}.apk"
    assets = [apk, apk.with_suffix(".apk.sha256")]
    if not all(asset.is_file() for asset in assets):
        raise ValueError("Verified APK and SHA256 file must both exist before publishing")
    release = publish_release(GitHub(), plan, NOTES.read_text(encoding="utf-8"), assets)
    summary(f"发布完成：{release['html_url']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "signing", "package", "publish"))
    command = parser.parse_args().command
    try:
        globals()[command]()
    except (ValueError, RuntimeError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release workflow failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
