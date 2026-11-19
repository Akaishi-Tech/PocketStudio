"""Allocate, verify and publish per-ABI Pocket Studio app releases."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlencode


APP = "PocketStudio"
FLAVORS = {"arm64-v8a": "arm64V8a", "armeabi-v7a": "armeabiV7a", "x86_64": "x86_64", "x86": "x86"}
PIN = Path("app/src/main/res/raw/release_signer.der")
APP_ID = "com.hcgstudio.pocketstudio"
SEMVER = r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
APP_MARK = re.compile(r"<!-- pocketstudio-release run=(\d+) versionCode=(\d+) -->")
APP_SEQUENCE = re.compile(r"<!-- pocketstudio-app-sequence (\d+) -->")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    return json.loads(path.read_text())


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def semver(value):
    match = re.fullmatch(SEMVER, value)
    require(match, f"Invalid version {value}")
    return tuple(map(int, match.groups()))


def next_app(releases, tags, run, bump, base, code):
    """A run ID survives Actions retries. Drafts reserve versions before any asset is published."""
    versions = [semver(base)]
    codes = [int(code)]
    for release in releases:
        tag = release["tag_name"]
        if not re.fullmatch("v" + SEMVER, tag):
            continue
        versions.append(semver(tag[1:]))
        marker = APP_MARK.search(release.get("body") or "")
        if marker:
            codes.append(int(marker[2]))
            if marker[1] == run:
                return tag[1:], int(marker[2]), not release["draft"]
    versions.extend(semver(t[1:]) for t in tags if re.fullmatch("v" + SEMVER, t))
    number = list(max(versions))
    index = ["major", "minor", "patch"].index(bump)
    number[index] += 1
    number[index + 1:] = [0] * (2 - index)
    return ".".join(map(str, number)), max(codes) + 1, False


def asset(release, name):
    return next((a for a in release.get("assets", []) if a["name"] == name), None)


def matches(item, sha, size):
    return bool(item and item["state"] == "uploaded" and item.get("digest") == "sha256:" + sha
                and item["size"] == size)


class GitHub:
    def __init__(self):
        self.repo = os.environ["GITHUB_REPOSITORY"]
        self.root = "repos/" + self.repo

    def api(self, path, *, method="GET", body=None, all_pages=False):
        args = ["gh", "api", self.root + "/" + path, "--method", method]
        if all_pages:
            args.extend(["--paginate", "--slurp"])
        if body is not None:
            args.extend(["--input", "-"])
        result = subprocess.check_output(args, input=json.dumps(body).encode() if body is not None else None)
        value = json.loads(result) if result else None
        return [row for page in value for row in page] if all_pages else value

    def releases(self):
        # The publishing token can see draft releases as well as public ones.
        return self.api("releases?per_page=100", all_pages=True)

    def create(self, tag, body):
        return self.api("releases", method="POST", body={"tag_name": tag, "body": body,
                        "draft": True, "target_commitish": os.environ["GITHUB_SHA"], "make_latest": "false"})

    def rename(self, item, name):
        return self.api(f"releases/assets/{item['id']}", method="PATCH", body={"name": name, "label": ""})

    def upload(self, rid, path, name):
        query = urlencode({"name": name})
        url = f"https://uploads.github.com/repos/{self.repo}/releases/{rid}/assets?{query}"
        mime = "application/vnd.android.package-archive"
        return json.loads(subprocess.check_output(["gh", "api", url, "--method", "POST",
                          "--header", "Content-Type: " + mime, "--input", str(path)]))

    def remove(self, item):
        self.api(f"releases/assets/{item['id']}", method="DELETE")


def prepare(gh, out):
    releases = gh.releases()
    sequence = int(os.environ["GITHUB_RUN_NUMBER"])
    if any(int(marker[1]) > sequence for rel in releases if not rel["draft"]
           and re.fullmatch("v" + SEMVER, rel["tag_name"])
           if (marker := APP_SEQUENCE.search(rel.get("body") or ""))):
        print("A newer manual dispatch has already published. Skipping this superseded run.")
        return None
    props = dict(line.split("=", 1) for line in Path("gradle.properties").read_text().splitlines() if "=" in line)
    tags = [t["name"] for t in gh.api("tags?per_page=100", all_pages=True)]
    version, code, complete = next_app(releases, tags, os.environ["GITHUB_RUN_ID"], os.environ["BUMP"],
                                       props["pocketStudioBaseVersion"], props["pocketStudioBaseVersionCode"])
    if complete:
        print(f"This run already published v{version}.")
        return None
    require(code <= 2100000000, "App versionCode exhausted")
    return {"appVersion": version, "appCode": code}


def verify_apk(path, abi, version, code):
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "37.0.0"
    signing = subprocess.check_output([tools / "apksigner", "verify", "--verbose", "--print-certs", path], text=True)
    signers = re.findall(r"^(?:Signer #\d+|V[\d.]+ Signer:) certificate SHA-256 digest: ([0-9a-f]{64})$",
                         signing, re.M)
    require("Number of signers: 1" in signing.splitlines() and set(signers) == {digest(PIN)},
            f"Untrusted APK signer: {path.name}")
    badging = subprocess.check_output([tools / "aapt", "dump", "badging", path], text=True)
    fields = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.M)
    require(fields and fields.groups() == (APP_ID, str(code), version), f"Wrong package or version: {path.name}")
    native = re.search(r"^native-code: (.*)$", badging, re.M)
    require(native and native[1].split() == [f"'{abi}'"], f"Wrong native ABI: {path.name}")


def apk_name(version, abi):
    return f"{APP}-{version}-{abi}.apk"


def build(plan, out):
    env = {**os.environ, "POCKETSTUDIO_VERSION_NAME": plan["appVersion"],
           "POCKETSTUDIO_VERSION_CODE": str(plan["appCode"])}
    subprocess.run(["./gradlew", "--no-daemon", ":app:assembleRelease"], env=env, check=True)
    directory = Path("app/build/outputs/apk")
    for abi, flavor in FLAVORS.items():
        variant = directory / flavor / "release"
        apk, = read_json(variant / "output-metadata.json")["elements"]
        output = out / apk_name(plan["appVersion"], abi)
        shutil.copyfile(variant / apk["outputFile"], output)
        verify_apk(output, abi, plan["appVersion"], plan["appCode"])


def replace_asset(gh, rid, path):
    """Upload before changing the canonical name. Keep the old bytes until the new asset is readable."""
    release = gh.api(f"releases/{rid}")
    sha, size = digest(path), path.stat().st_size
    old = asset(release, path.name)
    if matches(old, sha, size):
        if old.get("label"):
            updated = gh.rename(old, path.name)
            require(not updated.get("label"), "GitHub did not clear the asset label")
        return
    stage_name = f"stage-{sha}-{path.name}"
    staged = asset(release, stage_name)
    if not matches(staged, sha, size):
        if staged:
            gh.remove(staged)
        staged = gh.upload(rid, path, stage_name)
        require(matches(staged, sha, size), "GitHub did not confirm the staged asset digest")
    if old:
        gh.rename(old, f"previous-{old['id']}-{path.name}")
    try:
        current = gh.rename(staged, path.name)
        require(matches(current, sha, size) and not current.get("label"), "Published asset verification failed")
    except Exception:
        if old and not asset(gh.api(f"releases/{rid}"), path.name):
            gh.rename(old, path.name)
        raise


def publish(gh, plan, out):
    releases = gh.releases()
    tag = "v" + plan["appVersion"]
    code = plan["appCode"]
    run = os.environ["GITHUB_RUN_ID"]
    marker = f"<!-- pocketstudio-release run={run} versionCode={code} -->"
    release = next((r for r in releases if r["tag_name"] == tag), None)
    if release:
        require(marker in (release.get("body") or ""), f"{tag} belongs to another release")
        if not release["draft"]:
            return
    else:
        seq = int(os.environ["GITHUB_RUN_NUMBER"])
        body = f"Pocket Studio {plan['appVersion']} for Android.\n\n{marker}\n<!-- pocketstudio-app-sequence {seq} -->"
        release = gh.create(tag, body)
    rid = release["id"]
    names = tuple(apk_name(plan["appVersion"], abi) for abi in FLAVORS)
    for abi in FLAVORS:
        replace_asset(gh, rid, out / apk_name(plan["appVersion"], abi))
    # An interrupted run may have staged old-style APKs before the naming migration.
    # Remove our own obsolete files only after all replacement digests have been verified.
    legacy_names = tuple(f"{APP}-{abi}.apk" for abi in FLAVORS)
    for item in gh.api(f"releases/{rid}")["assets"]:
        name = item["name"]
        if name in legacy_names or (name.startswith(("previous-", "stage-")) and name.endswith(names + legacy_names)):
            gh.remove(item)
    newer = any(not r["draft"] and re.fullmatch("v" + SEMVER, r["tag_name"])
                and semver(r["tag_name"][1:]) > semver(tag[1:]) for r in releases)
    update = {"draft": False, "make_latest": "false" if newer else "true"}
    if release.get("name"):
        update["name"] = ""  # Resuming a draft created by a previous workflow revision.
    gh.api(f"releases/{rid}", method="PATCH", body=update)


def main():
    out = Path(os.environ["RUNNER_TEMP"]) / "pocketstudio-release"
    out.mkdir(exist_ok=True)
    plan_file = out / "plan.json"
    command, = sys.argv[1:]
    if command == "plan":
        plan = prepare(GitHub(), out)
        with open(os.environ["GITHUB_OUTPUT"], "a") as outputs:
            print("publish=" + ("true" if plan else "false"), file=outputs)
        if plan:
            write_json(plan_file, plan)
            print(json.dumps(plan))
    elif command == "build":
        build(read_json(plan_file), out)
    elif command == "publish":
        publish(GitHub(), read_json(plan_file), out)
    else:
        raise SystemExit("usage: release.py plan|build|publish")


if __name__ == "__main__":
    main()
