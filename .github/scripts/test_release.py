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

    def create(self, tag, title, body):
        assert not any(row["tag_name"] == tag for row in self.rows)
        row = dict(tag_name=tag, draft=True, name=title, body=body, id=self.new_id(), assets=[])
        self.rows.append(row)
        return copy.deepcopy(row)

    def upload(self, rid, path, name, label):
        row = next(v for v in self.rows if v["id"] == rid)
        assert not r.asset(row, name)
        data = path.read_bytes()
        item = dict(id=self.new_id(), name=name, label=label, size=len(data), state="uploaded",
                    digest="sha256:" + hashlib.sha256(data).hexdigest())
        self.files[item["id"]] = data
        row["assets"].append(item)
        return copy.deepcopy(item)

    def rename(self, old, name, label):
        if name == self.fail_rename:
            self.fail_rename = None
            raise OSError("Interrupted asset rename")
        item = next(a for row in self.rows for a in row["assets"] if a["id"] == old["id"])
        item.update(name=name, label=label)
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
        self.lock = {"version": "0.170.0", "buildId": "1" * 64}
        self.resolver = self.enterContext(patch.object(r.subprocess, "run", side_effect=self.resolve))

    def resolve(self, args, check):
        assert args[1:4] == [r.SCRIPT, "resolve", "--output"] and check
        r.write_json(Path(args[4]), self.lock)

    def build(self, plan):
        if plan["buildRuntime"]:
            apk = self.out / r.RUNTIME
            apk.write_bytes(("signed test fixture " + self.lock["buildId"]).encode())
            r.write_json(self.out / r.MANIFEST, dict(self.lock, package=r.RUNTIME_ID,
                         file=r.RUNTIME, versionCode=plan["runtimeCode"], sha256=r.digest(apk),
                         size=apk.stat().st_size, signerSha256=r.digest(r.PIN)))
        if plan["channel"] == "app":
            (self.out / r.APP).write_bytes(plan["appVersion"].encode())

    def test_manual_bootstrap_retry_and_daily_update(self):
        os.environ["CHANNEL"] = "scheduled"
        self.assertIsNone(r.prepare(self.gh, self.out))
        self.resolver.assert_not_called()
        os.environ["CHANNEL"] = "app"
        plan = r.prepare(self.gh, self.out)
        self.build(plan)
        self.assertEqual(plan["runtimeCode"], 1700002)
        r.publish(self.gh, plan, self.out)
        fixed, app = self.gh.releases()
        self.assertEqual({a["name"] for a in fixed["assets"]}, {r.RUNTIME, r.MANIFEST})
        self.assertEqual({a["name"] for a in app["assets"]}, {r.RUNTIME, r.APP})
        self.assertEqual(r.asset(fixed, r.RUNTIME)["digest"], r.asset(app, r.RUNTIME)["digest"])
        self.assertFalse(app["draft"])
        self.assertIsNone(r.prepare(self.gh, self.out))  # same manual run retried after publishing
        os.environ["CHANNEL"] = "scheduled"
        self.assertIsNone(r.prepare(self.gh, self.out))  # unchanged signed dependency lock
        self.lock["buildId"] = "2" * 64
        plan = r.prepare(self.gh, self.out)
        self.assertEqual(plan["runtimeCode"], 1700003)
        self.build(plan)
        self.gh.fail_rename = r.RUNTIME
        with self.assertRaises(OSError):
            r.publish(self.gh, plan, self.out)
        self.assertEqual(r.asset(self.gh.releases()[0], r.RUNTIME)["digest"], r.asset(fixed, r.RUNTIME)["digest"])
        r.publish(self.gh, plan, self.out)
        self.assertEqual(self.gh.releases()[1], app)  # daily release must never edit app releases
        self.assertIsNone(r.prepare(self.gh, self.out))
        os.environ.update(CHANNEL="app", GITHUB_RUN_ID="403", GITHUB_RUN_NUMBER="4")
        next_plan = r.prepare(self.gh, self.out)
        self.assertFalse(next_plan["buildRuntime"])
        self.assertEqual(next_plan["appCode"], int(r.APP_MARK.search(app["body"])[2]) + 1)
        self.assertEqual(r.digest(self.out / r.RUNTIME), r.asset(self.gh.releases()[0], r.RUNTIME)["digest"][7:])

    def test_superseded_retry(self):
        plan = r.prepare(self.gh, self.out)
        self.build(plan)
        r.publish(self.gh, plan, self.out)
        public = self.gh.releases()
        os.environ.update(GITHUB_RUN_ID="400", GITHUB_RUN_NUMBER="2")
        self.assertIsNone(r.prepare(self.gh, self.out))
        os.environ.update(CHANNEL="runtime", GITHUB_SHA="b" * 40)
        self.gh.commit_comparison = "behind"
        self.assertIsNone(r.prepare(self.gh, self.out))
        self.assertEqual(self.gh.releases(), public)

    def test_interrupted_initial_manifest(self):
        fixed = self.gh.create(r.TAG, "", "")
        (self.out / r.MANIFEST).write_bytes(b"")
        self.gh.upload(fixed["id"], self.out / r.MANIFEST, r.MANIFEST, "")
        self.gh.rows[0]["assets"][0]["state"] = "starter"
        os.environ["CHANNEL"] = "scheduled"
        self.assertIsNone(r.prepare(self.gh, self.out))  # an incomplete manual bootstrap stays manual
        os.environ["CHANNEL"] = "runtime"
        plan = r.prepare(self.gh, self.out)
        self.assertTrue(plan["buildRuntime"])
        self.build(plan)
        r.publish(self.gh, plan, self.out)
        self.assertIsNone(r.prepare(self.gh, self.out))

    def test_version_allocation(self):
        drafts = [{"tag_name": "v1.20.5", "draft": True, "body": "<!-- pocketstudio-release run=70 versionCode=80 -->"}]
        self.assertEqual(r.next_app(drafts, [], "70", "patch", "0.4.0", 4), ("1.20.5", 80, False))
        self.assertEqual(r.next_app(drafts, ["v1.21.7"], "71", "minor", "0.4.0", 4), ("1.22.0", 81, False))
        self.assertEqual(r.next_app(drafts, [], "71", "major", "0.4.0", 4), ("2.0.0", 81, False))


if __name__ == "__main__":
    unittest.main()
