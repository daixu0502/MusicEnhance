import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


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


if __name__ == "__main__":
    unittest.main()
