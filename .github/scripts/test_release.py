"""Offline release state-machine tests. Run with python3 -B .github/scripts/test_release.py."""
import copy
import hashlib
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import release as r


class FakeGitHub:
    def __init__(self):
        self.rows = []
        self.files = {}
        self.next_id = 0
        self.fail_rename = None
        self.commit_comparison = "ahead"

    def new_id(self):
        self.next_id += 1
        return self.next_id

    def releases(self):
        return copy.deepcopy(self.rows)

    def api(self, path, *, method="GET", body=None, all_pages=False):
        if path.startswith("tags?"):
            return []
        if path.startswith("compare/"):
            return {"status": self.commit_comparison}
        row = next(v for v in self.rows if v["id"] == int(path.split("/")[1]))
        if body:
            row.update(body)
        return copy.deepcopy(row)

    def create(self, tag, body):
        assert not any(row["tag_name"] == tag for row in self.rows)
        row = dict(tag_name=tag, draft=True, name=None, body=body, id=self.new_id(), assets=[])
        self.rows.append(row)
        return copy.deepcopy(row)

    def upload(self, rid, path, name):
        row = next(v for v in self.rows if v["id"] == rid)
        assert not r.asset(row, name)
        data = path.read_bytes()
        item = dict(id=self.new_id(), name=name, label=None, size=len(data), state="uploaded",
                    digest="sha256:" + hashlib.sha256(data).hexdigest())
        self.files[item["id"]] = data
        row["assets"].append(item)
        return copy.deepcopy(item)

    def rename(self, old, name):
        if name == self.fail_rename:
            self.fail_rename = None
            raise OSError("Interrupted asset rename")
        item = next(a for row in self.rows for a in row["assets"] if a["id"] == old["id"])
        item.update(name=name, label="")
        return copy.deepcopy(item)

    def remove(self, old):
        for row in self.rows:
            row["assets"][:] = [a for a in row["assets"] if a["id"] != old["id"]]
        del self.files[old["id"]]

    def download(self, item, path):
        path.write_bytes(self.files[item["id"]])
        assert r.matches(item, r.digest(path), path.stat().st_size)


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.out = Path(self.enterContext(TemporaryDirectory()))
        self.gh = FakeGitHub()
        self.enterContext(patch.dict(os.environ, CHANNEL="app", BUMP="patch", GITHUB_RUN_ID="401",
                                    GITHUB_RUN_NUMBER="3", GITHUB_SHA="a" * 40))

    def test_publishes_only_abi_apks_and_resumes_partial_upload(self):
        plan = r.prepare(self.gh, self.out)
        for abi in r.FLAVORS:
            (self.out / r.apk_name(plan["appVersion"], abi)).write_bytes(abi.encode())
        self.gh.fail_rename = r.apk_name(plan["appVersion"], "x86_64")
        with self.assertRaises(OSError):
            r.publish(self.gh, plan, self.out)
        self.assertTrue(self.gh.rows[0]["draft"])
        retry = r.prepare(self.gh, self.out)
        self.assertEqual(plan, retry)
        r.publish(self.gh, retry, self.out)
        release, = self.gh.releases()
        self.assertFalse(release["draft"])
        self.assertIsNone(release["name"])
        self.assertTrue(all(not a["label"] for a in release["assets"]))
        self.assertEqual({a["name"] for a in release["assets"]}, {f"PocketStudio-{plan['appVersion']}-{abi}.apk" for abi in r.FLAVORS})
        self.assertIsNone(r.prepare(self.gh, self.out))
        os.environ.update(GITHUB_RUN_ID="400", GITHUB_RUN_NUMBER="2")
        self.assertIsNone(r.prepare(self.gh, self.out))

    def test_resumes_unnamed_versioned_release_from_legacy_draft(self):
        plan = r.prepare(self.gh, self.out)
        version = plan["appVersion"]
        code = plan["appCode"]
        self.gh.create("v" + version, f"<!-- pocketstudio-release run=401 versionCode={code} -->")
        draft = self.gh.rows[0]
        draft["name"] = "Old release title"
        for abi in r.FLAVORS:
            path = self.out / r.apk_name(version, abi)
            path.write_bytes(abi.encode())
            self.gh.upload(draft["id"], path, f"PocketStudio-{abi}.apk")
            self.gh.upload(draft["id"], path, path.name)
            draft["assets"][-1]["label"] = "old APK"
        r.publish(self.gh, plan, self.out)
        self.assertFalse(draft["name"])
        self.assertTrue(all(not a["label"] for a in draft["assets"]))
        self.assertEqual({a["name"] for a in draft["assets"]}, {r.apk_name(version, abi) for abi in r.FLAVORS})

    def test_version_allocation(self):
        drafts = [{"tag_name": "v1.20.5", "draft": True, "body": "<!-- pocketstudio-release run=70 versionCode=80 -->"}]
        self.assertEqual(r.next_app(drafts, [], "70", "patch", "0.4.0", 4), ("1.20.5", 80, False))
        self.assertEqual(r.next_app(drafts, ["v1.21.7"], "71", "minor", "0.4.0", 4), ("1.22.0", 81, False))
        self.assertEqual(r.next_app(drafts, [], "71", "major", "0.4.0", 4), ("2.0.0", 81, False))


if __name__ == "__main__":
    unittest.main()
