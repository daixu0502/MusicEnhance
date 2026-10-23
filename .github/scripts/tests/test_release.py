import importlib.util
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location("musicenhance_release", Path(__file__).parents[1] / "release.py")
release = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = release
SPEC.loader.exec_module(release)


def item(identifier, tag, prerelease=False, draft=False, published="2026-09-23T00:00:00Z"):
    return {"id": identifier, "tag_name": tag, "prerelease": prerelease, "draft": draft,
            "published_at": published, "target_commitish": "abc", "upload_url": "https://uploads.github.com/assets{?name}",
            "html_url": "https://github.com/owner/repo/releases/tag/" + tag}


class FakeGitHub:
    repository = "owner/repo"
    server_url = "https://github.com"

    def __init__(self, releases=None, fail_upload=False):
        self.releases = releases or []
        self.calls = []
        self.fail_upload = fail_upload

    def pages(self, endpoint):
        return self.releases if endpoint == "releases" else [{"id": 100, "name": "test.apk"}]

    def request(self, method, endpoint, payload=None, binary=None):
        self.calls.append((method, endpoint, payload))
        if binary is not None and self.fail_upload:
            raise RuntimeError("upload failed")
        if binary is not None or method == "DELETE":
            return {}
        return item(1, payload.get("tag_name", "v2.0"), payload.get("prerelease", False), payload.get("draft", True))


class VersionAndNotesTests(unittest.TestCase):
    def test_platform_packages_keep_required_minor_versions(self):
        metadata = {"versionName": "2.0.5", "versionCode": 41, "buildToolsVersion": "36.0.0"}
        for major, minor, expected in (
            (35, 0, "platforms;android-35"),
            (36, 0, "platforms;android-36"),
            (36, 1, "platforms;android-36.1"),
            (37, 0, "platforms;android-37.0"),
            (37, 2, "platforms;android-37.2"),
        ):
            with self.subTest(major=major, minor=minor):
                self.assertEqual(expected, release.sdk_platform_package({
                    **metadata, "compileSdk": major, "compileSdkMinor": minor,
                }))
        self.assertEqual("platforms;android-37.0", release.sdk_platform_package({**metadata, "compileSdk": 37}))

    def test_invalid_minor_versions_are_rejected(self):
        metadata = {"versionName": "2.0.5", "versionCode": 41, "compileSdk": 37, "buildToolsVersion": "36.0.0"}
        for minor in (-1, "0", True, "0;tools"):
            with self.subTest(minor=minor), self.assertRaises(ValueError):
                release.sdk_platform_package({**metadata, "compileSdkMinor": minor})

    def test_numeric_versions_are_stable_and_beta_variants_are_previews(self):
        for value in ("2.0", "2.0.1", "12.31.0"):
            self.assertFalse(release.classify_version(value))
        for value in ("2.1-beta", "2.1-beta1", "2.1-beta.2", "2.1.0-BETA.3"):
            self.assertTrue(release.classify_version(value))

    def test_unknown_suffixes_and_path_characters_fail_instead_of_publishing_stable(self):
        for value in ("2.1-rc.1", "2.1-alpha", "2.1/dev", "v2.0", "2.0\nfoo", "2.01", "2.0;false"):
            with self.assertRaises(ValueError):
                release.classify_version(value)

    def test_each_channel_uses_its_own_most_recent_published_release(self):
        releases = [item(1, "v1.9", published="2026-09-20T00:00:00Z"),
                    item(2, "v2.0-beta.1", True, published="2026-09-21T00:00:00Z"),
                    item(3, "v2.0-beta.2", True, published="2026-09-22T00:00:00Z"),
                    item(4, "v2.0", draft=True), item(5, "v2.1-beta.1", True, draft=True)]
        self.assertEqual("v1.9", release.previous_release(releases, False, "v2.0")["tag_name"])
        self.assertEqual("v2.0-beta.2", release.previous_release(releases, True, "v2.1-beta.1")["tag_name"])

    def test_first_stable_does_not_use_a_beta_as_its_base(self):
        self.assertIsNone(release.previous_release([item(1, "v2.0-beta.1", True)], False, "v2.0"))
        self.assertIsNone(release.previous_release([item(1, "v2.0")], True, "v2.1-beta.1"))

    def test_notes_escape_commit_messages_and_link_to_the_exact_base(self):
        notes = release.release_notes("owner/repo", "https://github.com", "2.0", False, "v1.9",
                                      [("a" * 40, "fix: [lyrics] <layout> *blur*")])
        self.assertIn("compare/v1.9...v2.0", notes)
        self.assertIn(r"\[lyrics\] &lt;layout&gt; \*blur\*", notes)
        self.assertIn("a" * 40, notes)

    def test_first_release_and_empty_range_have_explicit_notes(self):
        first = release.release_notes("owner/repo", "https://github.com", "2.0", False, None, [])
        self.assertIn("首个正式版", first)
        self.assertIn("/commits/v2.0", first)

    def test_long_history_is_bounded_with_full_comparison_link(self):
        notes = release.release_notes("owner/repo", "https://github.com", "2.0", False, "v1.9",
                                      [("a" * 40, "改动" * 100) for _ in range(2000)])
        self.assertLess(len(notes.encode()), release.MAX_NOTES_BYTES)
        self.assertIn("共 2000 项提交", notes)
        self.assertIn("compare/v1.9...v2.0", notes)


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "test.apk"
        self.apk.write_bytes(b"test-only")
        self.plan = {"tag": "v2.0", "commit": "abc", "prerelease": False}

    def test_assets_are_uploaded_while_draft_before_publishing_stable(self):
        api = FakeGitHub()
        release.publish_release(api, self.plan, "notes", [self.apk])
        self.assertTrue(api.calls[0][2]["draft"])
        self.assertTrue(any(call[1].startswith("https://uploads.") for call in api.calls[:-1]))
        self.assertEqual({"draft": False, "prerelease": False, "make_latest": "true"}, api.calls[-1][2])

    def test_beta_does_not_replace_the_latest_stable_release(self):
        api = FakeGitHub()
        release.publish_release(api, {**self.plan, "prerelease": True}, "notes", [self.apk])
        self.assertEqual({"draft": False, "prerelease": True, "make_latest": "false"}, api.calls[-1][2])

    def test_release_title_uses_version_name_instead_of_tag(self):
        api = FakeGitHub()
        release.publish_release(api, {**self.plan, "versionName": "2.0"}, "notes", [self.apk])
        self.assertEqual("2.0", api.calls[0][2]["name"])
        self.assertEqual("v2.0", api.calls[0][2]["tag_name"])

    def test_failed_upload_never_publishes_the_draft(self):
        api = FakeGitHub(fail_upload=True)
        with self.assertRaises(RuntimeError):
            release.publish_release(api, self.plan, "notes", [self.apk])
        self.assertFalse(any(call[2] and call[2].get("draft") is False for call in api.calls))

    def test_public_release_is_never_overwritten_on_rerun(self):
        api = FakeGitHub([item(1, "v2.0")])
        release.publish_release(api, self.plan, "new notes", [self.apk])
        self.assertEqual([], api.calls)

    def test_draft_retry_replaces_matching_assets_and_preserves_the_release(self):
        api = FakeGitHub([item(1, "v2.0", draft=True)])
        with patch.object(release, "resolve_commit", return_value="abc"):
            release.publish_release(api, self.plan, "notes", [self.apk])
        self.assertEqual("PATCH", api.calls[0][0])
        self.assertIn(("DELETE", "releases/assets/100", None), api.calls)

    def test_draft_from_another_commit_is_rejected(self):
        api = FakeGitHub([item(1, "v2.0", draft=True)])
        with patch.object(release, "resolve_commit", return_value="different"):
            with self.assertRaises(ValueError):
                release.publish_release(api, self.plan, "notes", [self.apk])
        self.assertEqual([], api.calls)


class GitHistoryTests(unittest.TestCase):
    def test_real_git_ranges_include_all_beta_changes_in_the_next_stable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(["git", "-c", "user.name=Workflow Test", "-c", "user.email=test@example.invalid", *args],
                                      cwd=root, check=True, capture_output=True, text=True).stdout.strip()
            git("init")
            git("commit", "--allow-empty", "-m", "initial")
            git("tag", "v1.9")
            git("commit", "--allow-empty", "-m", "first beta change")
            git("tag", "v2.0-beta.1")
            git("commit", "--allow-empty", "-m", "second beta change")
            head = git("rev-parse", "HEAD")
            with patch.object(release, "ROOT", root):
                stable = release.commit_messages("v1.9", head)
                beta = release.commit_messages("v2.0-beta.1", head)
                first = release.commit_messages(None, head)
            self.assertEqual(["first beta change", "second beta change"], [subject for _, subject in stable])
            self.assertEqual(["second beta change"], [subject for _, subject in beta])
            self.assertEqual(3, len(first))


class DistributionGitHub(FakeGitHub):
    repository = release.LSPOSED_REPOSITORY

    def request(self, method, endpoint, payload=None, binary=None):
        if method == "GET":
            self.calls.append((method, endpoint, payload))
            if endpoint == "":
                return {"default_branch": "main", "permissions": {"push": True}}
            if endpoint.startswith("commits/"):
                return {"sha": "distribution-commit"}
            raise AssertionError(endpoint)
        return super().request(method, endpoint, payload, binary)


class MirrorTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.enterContext(patch.object(release, "WORK", self.root))
        self.metadata = {"versionName": "3.2.1", "versionCode": 30201, "compileSdk": 37,
                         "compileSdkMinor": 0, "buildToolsVersion": "36.0.0"}
        self.apk_name = "MusicEnhance-v3.2.1.apk"
        self.contents = {self.apk_name: b"same signed source APK"}
        digest = hashlib.sha256(self.contents[self.apk_name]).hexdigest()
        self.contents[self.apk_name + ".sha256"] = f"{digest}  {self.apk_name}\n".encode()
        self.source_release = {**item(9, "v3.2.1"), "body": "source changelog",
                               "assets": [{"name": name} for name in self.contents]}
        self.source = Mock(repository="owner/source", server_url="https://github.com")
        self.source.request.return_value = self.source_release
        self.target = DistributionGitHub()
        self.download = self.enterContext(patch.object(
            release, "download_public_asset", side_effect=lambda source, asset: self.contents[asset["name"]]))
        self.verify_metadata = self.enterContext(patch.object(release, "verify_apk_metadata"))

    def test_distribution_tag_title_notes_and_apk_match_the_public_source(self):
        with patch.object(release, "resolve_commit", side_effect=AssertionError("must not resolve source history")):
            release.mirror_release(self.source, self.target, self.metadata)
        payload = next(call[2] for call in self.target.calls if call[0] == "POST" and call[1] == "releases")
        self.assertEqual("30201-3.2.1", payload["tag_name"])
        self.assertEqual("3.2.1", payload["name"])
        self.assertEqual("source changelog", payload["body"])
        self.assertEqual("distribution-commit", payload["target_commitish"])
        self.source.request.assert_called_once_with("GET", "releases/tags/v3.2.1")
        self.assertEqual(self.contents[self.apk_name], (self.root / "lsposed" / self.apk_name).read_bytes())
        self.verify_metadata.assert_called_once_with(self.root / "lsposed" / self.apk_name, self.metadata)
        uploads = [call for call in self.target.calls if call[1].startswith("https://uploads.")]
        self.assertEqual(2, len(uploads))
        self.assertTrue(all(call[0] == "POST" for call in uploads))
        self.assertEqual({"draft": False, "prerelease": False, "make_latest": "true"}, self.target.calls[-1][2])

    def test_already_published_distribution_release_is_not_overwritten(self):
        self.target.releases = [item(1, "30201-3.2.1")]
        release.mirror_release(self.source, self.target, self.metadata)
        self.assertEqual([], self.target.calls)
        self.source.request.assert_not_called()
        self.download.assert_not_called()

    def test_checksum_mismatch_stops_before_creating_any_distribution_release(self):
        self.contents[self.apk_name] = b"different APK"
        with self.assertRaisesRegex(ValueError, "SHA256"):
            release.mirror_release(self.source, self.target, self.metadata)
        self.assertEqual([], self.target.calls)

    def test_checksum_for_another_filename_is_rejected(self):
        self.contents[self.apk_name + ".sha256"] = self.contents[self.apk_name + ".sha256"].replace(
            self.apk_name.encode(), b"another.apk")
        with self.assertRaisesRegex(ValueError, "SHA256"):
            release.mirror_release(self.source, self.target, self.metadata)
        self.assertEqual([], self.target.calls)

    def test_wrong_apk_version_stops_before_any_distribution_mutation(self):
        self.verify_metadata.side_effect = ValueError("Source APK package/version differs")
        with self.assertRaisesRegex(ValueError, "package/version"):
            release.mirror_release(self.source, self.target, self.metadata)
        self.assertEqual([], self.target.calls)

    def test_missing_source_checksum_stops_before_publication(self):
        self.source_release["assets"] = [{"name": self.apk_name}]
        with self.assertRaisesRegex(ValueError, "exactly one"):
            release.mirror_release(self.source, self.target, self.metadata)
        self.assertEqual([], self.target.calls)

    def test_source_must_be_public_and_use_the_matching_channel(self):
        for field in ("draft", "prerelease"):
            with self.subTest(field=field):
                self.source.request.return_value = {**self.source_release, field: True}
                with self.assertRaisesRegex(ValueError, "public.*channel"):
                    release.mirror_release(self.source, self.target, self.metadata)
        self.download.assert_not_called()

    def test_beta_keeps_its_suffix_and_does_not_become_latest_stable(self):
        version = "3.3.0-beta.1"
        apk_name = f"MusicEnhance-v{version}.apk"
        apk = b"beta APK"
        checksum = f"{hashlib.sha256(apk).hexdigest()}  {apk_name}\n".encode()
        self.contents = {apk_name: apk, apk_name + ".sha256": checksum}
        self.source.request.return_value = {**item(9, "v" + version, prerelease=True), "body": "beta changes",
                                            "assets": [{"name": name} for name in self.contents]}
        release.mirror_release(self.source, self.target, {**self.metadata, "versionName": version, "versionCode": 30300})
        payload = next(call[2] for call in self.target.calls if call[0] == "POST" and call[1] == "releases")
        self.assertEqual("30300-3.3.0-beta.1", payload["tag_name"])
        self.assertEqual(version, payload["name"])
        self.assertEqual({"draft": False, "prerelease": True, "make_latest": "false"}, self.target.calls[-1][2])

    def test_failed_distribution_upload_keeps_draft_for_retry(self):
        self.target.fail_upload = True
        with self.assertRaisesRegex(RuntimeError, "upload failed"):
            release.mirror_release(self.source, self.target, self.metadata)
        self.assertFalse(any(call[2] and call[2].get("draft") is False for call in self.target.calls))

    def test_draft_retry_resolves_the_distribution_repository_history(self):
        self.target.releases = [{**item(1, "30201-3.2.1", draft=True), "target_commitish": "main"}]
        with patch.object(release, "resolve_commit", side_effect=AssertionError("wrong repository")):
            release.mirror_release(self.source, self.target, self.metadata)
        self.assertIn(("GET", "commits/main", None), self.target.calls)
        self.assertTrue(any(method == "PATCH" and endpoint == "releases/1" and payload.get("draft") is True
                            for method, endpoint, payload in self.target.calls))
        self.assertFalse(self.target.calls[-1][2]["draft"])

    def test_distribution_draft_target_conflict_is_not_overwritten(self):
        self.target.releases = [{**item(1, "30201-3.2.1", draft=True), "target_commitish": "old-commit"}]
        request = self.target.request
        def changed_target(method, endpoint, payload=None, binary=None):
            if endpoint == "commits/old-commit":
                return {"sha": "old-commit"}
            return request(method, endpoint, payload, binary)
        self.target.request = changed_target
        with self.assertRaisesRegex(ValueError, "another commit"):
            release.mirror_release(self.source, self.target, self.metadata)
        self.assertFalse(any(method != "GET" for method, _, _ in self.target.calls))

    def test_rerun_can_mirror_without_a_build_plan_or_local_build_artifacts(self):
        metadata_file = self.root / "app/build/release-metadata.json"
        metadata_file.parent.mkdir(parents=True)
        metadata_file.write_text(json.dumps(self.metadata), encoding="utf-8")
        with patch.object(release, "ROOT", self.root), patch.object(release, "GitHub", return_value=self.source), \
                patch.object(release, "lsposed_client", return_value=self.target), patch.object(release, "summary"):
            release.mirror()
        self.source.request.assert_called_once_with("GET", "releases/tags/v3.2.1")
        self.assertFalse(self.target.calls[-1][2]["draft"])

    def test_missing_cross_repository_token_fails_with_the_secret_name(self):
        with patch.dict(os.environ, {"LSPOSED_TOKEN": ""}):
            with self.assertRaisesRegex(ValueError, "LSPOSED_TOKEN"):
                release.lsposed_client()

    def test_cross_repository_token_is_scoped_to_the_distribution_client(self):
        with patch.dict(os.environ, {"LSPOSED_TOKEN": "distribution-only", "GH_TOKEN": "source-only",
                                     "GITHUB_REPOSITORY": "owner/source"}):
            target = release.lsposed_client()
            source = release.GitHub()
        self.assertEqual(release.LSPOSED_REPOSITORY, target.repository)
        self.assertEqual("distribution-only", target.token)
        self.assertEqual("owner/source", source.repository)
        self.assertEqual("source-only", source.token)

    def test_publishing_access_check_rejects_a_read_only_account(self):
        self.target.request = Mock(return_value={"permissions": {"push": False}})
        with patch.object(release, "lsposed_client", return_value=self.target):
            with self.assertRaisesRegex(ValueError, "write access"):
                release.check_lsposed()


class DownloadTests(unittest.TestCase):
    def test_public_download_does_not_send_either_repository_token(self):
        source = Mock(server_url="https://github.com", token="source-token")
        with patch.object(release, "urlopen") as urlopen:
            urlopen.return_value.__enter__.return_value.read.return_value = b"signed APK"
            data = release.download_public_asset(source, {
                "browser_download_url": "https://github.com/owner/repo/releases/download/v3.2.1/app.apk",
            })
        self.assertEqual(b"signed APK", data)
        request = urlopen.call_args.args[0]
        self.assertIsNone(request.get_header("Authorization"))

    def test_unexpected_download_host_is_rejected_without_network_access(self):
        with patch.object(release, "urlopen") as urlopen:
            with self.assertRaisesRegex(ValueError, "unexpected"):
                release.download_public_asset(Mock(server_url="https://github.com"), {
                    "browser_download_url": "https://unrelated.invalid/app.apk",
                })
        urlopen.assert_not_called()


class ApkMetadataTests(unittest.TestCase):
    def test_package_and_both_versions_must_match_the_planned_tag(self):
        metadata = {"versionName": "3.2.1", "versionCode": 30201, "buildToolsVersion": "36.0.0"}
        cases = [(release.APPLICATION_ID, 30201, "3.2.1", True),
                 (release.APPLICATION_ID, 30200, "3.2.1", False),
                 (release.APPLICATION_ID, 30201, "3.2.0", False),
                 ("another.module", 30201, "3.2.1", False)]
        for package, code, version, valid in cases:
            with self.subTest(package=package, code=code, version=version), \
                    patch.dict(os.environ, {"ANDROID_HOME": "/android-sdk"}), \
                    patch.object(release.subprocess, "run", return_value=Mock(
                        stdout=f"package: name='{package}' versionCode='{code}' versionName='{version}'\n")):
                if valid:
                    release.verify_apk_metadata(Path("app.apk"), metadata)
                else:
                    with self.assertRaisesRegex(ValueError, "package/version"):
                        release.verify_apk_metadata(Path("app.apk"), metadata)

    def test_skipped_source_release_still_exports_build_tools_for_sync_checks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata_file = root / "app/build/release-metadata.json"
            metadata_file.parent.mkdir(parents=True)
            metadata_file.write_text(json.dumps({"versionName": "3.2.1", "versionCode": 30201,
                                                "compileSdk": 37, "buildToolsVersion": "36.0.0"}), encoding="utf-8")
            api = FakeGitHub([item(1, "v3.2.1")])
            with patch.object(release, "ROOT", root), patch.object(release, "GitHub", return_value=api), \
                    patch.dict(os.environ, {"GITHUB_REF": "refs/heads/main"}), \
                    patch.object(release, "output") as output, patch.object(release, "summary"):
                release.prepare()
            output.assert_any_call("publish", "false")
            output.assert_any_call("build_tools", "36.0.0")
            output.assert_any_call("version", "3.2.1")


if __name__ == "__main__":
    unittest.main()
