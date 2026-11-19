"""Build/publish coordination for the Pocket Studio and Codex GitHub releases."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlencode


TAG = "plugin-store"
RUNTIME = "PocketStudio-Codex-Plugin.apk"
APP = "PocketStudio.apk"
MANIFEST = "plugin-store.json"
SCRIPT = "scripts/build-codex-plugin.py"
PIN = Path("app/src/main/res/raw/release_signer.der")
APP_ID = "com.hcgstudio.pocketstudio"
RUNTIME_ID = APP_ID + ".codexplugin"
SEMVER = r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
APP_MARK = re.compile(r"<!-- pocketstudio-release run=(\d+) versionCode=(\d+) -->")
APP_SEQUENCE = re.compile(r"<!-- pocketstudio-app-sequence (\d+) -->")
RUNTIME_SOURCE = re.compile(r"<!-- pocketstudio-runtime-source ([0-9a-f]{40}) -->")
CODEX_LABEL = re.compile(r"Codex Plugin ([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9]+)?) \(([0-9]+)\)")
RUNTIME_TITLE = "Pocket Studio Plugin Store"


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


def plugin_version(value):
    version, _, revision = value.partition("-")
    return (*semver(version), int(revision or 0))


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


def runtime_label(meta):
    return f"Codex Plugin {meta['version']} ({meta['versionCode']})"


def validate_store(store):
    require(store["schemaVersion"] == 1 and isinstance(store["plugins"], list), "Invalid plugin catalog")
    ids, files = set(), set()
    for plugin in store["plugins"]:
        package, name = plugin["package"], plugin["file"]
        require(re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+", package)
                and package != APP_ID and package not in ids, "Invalid or duplicate plugin package")
        require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", name) and name not in files and name != APP,
                "Invalid or duplicate plugin filename")
        ids.add(package)
        files.add(name)
        require(plugin["name"].strip() and plugin["version"].strip(), "Missing plugin name or version")
        require(0 < plugin["size"] <= 1_500_000_000 and 0 < plugin["versionCode"] <= 2_100_000_000,
                "Invalid plugin size or version code")
        require(re.fullmatch("[0-9a-f]{64}", plugin["sha256"]) and plugin["signerSha256"] == digest(PIN),
                "Invalid plugin digest or signer")
        require(isinstance(plugin["abis"], list) and all(isinstance(abi, str) and abi for abi in plugin["abis"]),
                "Invalid plugin ABI list")
    return store


def published_store(gh, release, out):
    manifest = asset(release, MANIFEST)
    if not manifest or manifest["state"] != "uploaded":
        # A runner can stop between renaming the previous catalog and promoting its replacement.
        backups = [item for item in release.get("assets", [])
                   if item["state"] == "uploaded" and re.fullmatch(r"previous-\d+-" + re.escape(MANIFEST), item["name"])]
        manifest = max(backups, key=lambda item: item["id"], default=None)
        if not manifest:
            return {"schemaVersion": 1, "plugins": []}
    require(0 < manifest["size"] <= 262144, "Invalid plugin catalog size")
    dest = out / "published-store.json"
    gh.download(manifest, dest)
    return validate_store(read_json(dest))


def codex_entry(store):
    return next((entry for entry in store["plugins"] if entry["package"] == RUNTIME_ID), None)


def validate_manifest(meta):
    require(meta["file"] == RUNTIME and meta["package"] == RUNTIME_ID, "Invalid runtime identity")
    require(isinstance(meta["versionCode"], int) and 0 < meta["versionCode"] <= 2100000000,
            "Invalid runtime versionCode")
    require(CODEX_LABEL.fullmatch(runtime_label(meta)), "Invalid runtime version label")
    for field in ("buildId", "sha256", "signerSha256"):
        require(re.fullmatch("[0-9a-f]{64}", meta[field]), f"Invalid {field}")
    require(meta["signerSha256"] == digest(PIN), "Runtime signing certificate does not match the app pin")


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

    def download(self, item, path):
        with path.open("wb") as out:
            subprocess.run(["gh", "api", f"{self.root}/releases/assets/{item['id']}",
                            "-H", "Accept: application/octet-stream"], stdout=out, check=True)
        require(matches(item, digest(path), path.stat().st_size), f"GitHub asset digest mismatch: {path.name}")

    def create(self, tag, title, body):
        return self.api("releases", method="POST", body={"tag_name": tag, "name": title, "body": body,
                        "draft": True, "target_commitish": os.environ["GITHUB_SHA"], "make_latest": "false"})

    def rename(self, item, name, label):
        return self.api(f"releases/assets/{item['id']}", method="PATCH", body={"name": name, "label": label})

    def upload(self, rid, path, name, label):
        query = urlencode({"name": name, "label": label})
        url = f"https://uploads.github.com/repos/{self.repo}/releases/{rid}/assets?{query}"
        mime = "application/vnd.android.package-archive" if path.suffix == ".apk" else "application/json"
        return json.loads(subprocess.check_output(["gh", "api", url, "--method", "POST",
                          "--header", "Content-Type: " + mime, "--input", str(path)]))

    def remove(self, item):
        self.api(f"releases/assets/{item['id']}", method="DELETE")


def prepare(gh, out):
    channel = os.environ["CHANNEL"]
    require(channel in {"app", "plugins", "scheduled"}, "Unknown release channel")
    releases = gh.releases()
    fixed = next((r for r in releases if r["tag_name"] == TAG), {})
    migrating = any(r["tag_name"] == "codex-runtime" and not r["draft"] for r in releases)
    if channel == "scheduled" and (not fixed or fixed["draft"]) and not migrating:
        print("First publication is manual. Skipping the scheduled run.")
        return None
    if channel == "app":
        sequence = int(os.environ["GITHUB_RUN_NUMBER"])
        if any(int(marker[1]) > sequence for rel in releases if not rel["draft"]
               and re.fullmatch("v" + SEMVER, rel["tag_name"])
               if (marker := APP_SEQUENCE.search(rel.get("body") or ""))):
            print("A newer manual dispatch has already published. Skipping this superseded run.")
            return None
    previous_source = RUNTIME_SOURCE.search(fixed.get("body") or "")
    source = os.environ["GITHUB_SHA"]
    require(re.fullmatch("[0-9a-f]{40}", source), "Invalid source commit")
    if previous_source and previous_source[1] != source:
        comparison = gh.api(f"compare/{previous_source[1]}...{source}")
        if comparison["status"] not in ("identical", "ahead"):
            print("The plugin store already uses a newer or diverged source revision. Skipping this run.")
            return None
    plan = {"channel": channel}
    if channel == "app":
        props = dict(line.split("=", 1) for line in Path("gradle.properties").read_text().splitlines() if "=" in line)
        tags = [t["name"] for t in gh.api("tags?per_page=100", all_pages=True)]
        version, code, complete = next_app(releases, tags, os.environ["GITHUB_RUN_ID"], os.environ["BUMP"],
                                           props["pocketStudioBaseVersion"], props["pocketStudioBaseVersionCode"])
        if complete:
            print(f"This run already published v{version}.")
            return None
        require(code <= 2100000000, "App versionCode exhausted")
        plan.update(appVersion=version, appCode=code)
    lock_path = out / "codex-lock.json"
    subprocess.run([sys.executable, SCRIPT, "resolve", "--output", str(lock_path)], check=True)
    lock = read_json(lock_path)
    upstream = plugin_version(lock["version"])
    require(re.fullmatch("[0-9a-f]{64}", lock["buildId"]), "Invalid resolved Codex build ID")
    apk = asset(fixed, RUNTIME)
    store = published_store(gh, fixed, out)
    previous = codex_entry(store)
    established = False
    if previous:
        validate_manifest(previous)
        require(upstream >= plugin_version(previous["version"]), "Upstream Codex version would downgrade installed devices")
        established = (not fixed["draft"] and matches(apk, previous["sha256"], previous["size"])
                       and matches(asset(fixed, MANIFEST), digest(out / "published-store.json"),
                                   (out / "published-store.json").stat().st_size)
                       and apk.get("label") == runtime_label(previous)
                       and previous["buildId"] == lock["buildId"] and previous["version"] == lock["version"])
    plan["buildRuntime"] = not established
    if established:
        if channel != "app":
            print(f"Codex {lock['version']} and its build inputs are current.")
            return None
        write_json(out / MANIFEST, store)
        gh.download(apk, out / RUNTIME)
        plan["runtimeCode"] = previous["versionCode"]
    else:
        codes = [int(m[2]) for r in releases for a in r.get("assets", [])
                 if (m := CODEX_LABEL.fullmatch(a.get("label") or ""))]
        if previous:
            codes.append(previous["versionCode"])
        major, minor, patch, revision = upstream
        legacy_floor = major * 100000000 + minor * 10000 + patch * 100 + revision + 1
        plan["runtimeCode"] = max(legacy_floor + 1, max(codes, default=0) + 1)
        require(plan["runtimeCode"] <= 2100000000, "Codex versionCode exhausted")
    return plan


def verify_apk(path, package, version, code):
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "37.0.0"
    signing = subprocess.check_output([tools / "apksigner", "verify", "--verbose", "--print-certs", path], text=True)
    signers = re.findall(r"^(?:Signer #\d+|V[\d.]+ Signer:) certificate SHA-256 digest: ([0-9a-f]{64})$",
                         signing, re.M)
    require("Number of signers: 1" in signing.splitlines() and set(signers) == {digest(PIN)},
            f"Untrusted APK signer: {path.name}")
    badging = subprocess.check_output([tools / "aapt", "dump", "badging", path], text=True)
    fields = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.M)
    require(fields and fields.groups() == (package, str(code), version), f"Wrong package or version: {path.name}")


def build(plan, out):
    lock_path = out / "codex-lock.json"
    lock = read_json(lock_path)
    if plan["buildRuntime"]:
        subprocess.run([sys.executable, SCRIPT, "build", "--lock", str(lock_path), "--out", str(out),
                        "--version-code", str(plan["runtimeCode"])], check=True)
    meta = codex_entry(validate_store(read_json(out / MANIFEST)))
    require(meta is not None, "Missing Codex plugin entry")
    validate_manifest(meta)
    require(meta["buildId"] == lock["buildId"] and meta["version"] == lock["version"]
            and meta["versionCode"] == plan["runtimeCode"], "Built runtime does not match the release plan")
    require((out / RUNTIME).stat().st_size == meta["size"] and digest(out / RUNTIME) == meta["sha256"],
            "Built runtime does not match its manifest")
    verify_apk(out / RUNTIME, RUNTIME_ID, meta["version"], meta["versionCode"])
    if plan["channel"] == "app":
        env = {**os.environ, "POCKETSTUDIO_VERSION_NAME": plan["appVersion"],
               "POCKETSTUDIO_VERSION_CODE": str(plan["appCode"])}
        subprocess.run(["./gradlew", "--no-daemon", ":app:assembleRelease"], env=env, check=True)
        shutil.copyfile("app/build/outputs/apk/release/app-release.apk", out / APP)
        verify_apk(out / APP, APP_ID, plan["appVersion"], plan["appCode"])


def replace_asset(gh, rid, path, label):
    """Upload before changing the canonical name. Keep the old bytes until the new asset is readable."""
    release = gh.api(f"releases/{rid}")
    sha, size = digest(path), path.stat().st_size
    old = asset(release, path.name)
    if matches(old, sha, size):
        if old.get("label") != label:
            gh.rename(old, path.name, label)
        return
    stage_name = f"stage-{sha}-{path.name}"
    staged = asset(release, stage_name)
    if not matches(staged, sha, size):
        if staged:
            gh.remove(staged)
        staged = gh.upload(rid, path, stage_name, label)
        require(matches(staged, sha, size), "GitHub did not confirm the staged asset digest")
    if old:
        gh.rename(old, f"previous-{old['id']}-{path.name}", old.get("label") or "")
    try:
        current = gh.rename(staged, path.name, label)
        require(matches(current, sha, size), "Published asset digest mismatch")
    except Exception:
        if old and not asset(gh.api(f"releases/{rid}"), path.name):
            gh.rename(old, path.name, old.get("label") or "")
        raise


def publish(gh, plan, out):
    meta = codex_entry(validate_store(read_json(out / MANIFEST)))
    require(meta is not None, "Missing Codex plugin entry")
    releases = gh.releases()
    if plan["buildRuntime"]:
        release = next((r for r in releases if r["tag_name"] == TAG), None)
        source = os.environ["GITHUB_SHA"]
        notes = f"Install and update Pocket Studio plugins from Settings.\n\n<!-- pocketstudio-runtime-source {source} -->"
        if not release:
            release = gh.create(TAG, RUNTIME_TITLE, notes)
        rid = release["id"]
        if release["body"] != notes:
            # Reserve this source revision before moving assets, including if the runner is interrupted.
            gh.api(f"releases/{rid}", method="PATCH", body={"body": notes, "make_latest": "false"})
        replace_asset(gh, rid, out / RUNTIME, runtime_label(meta))
        store = published_store(gh, release, out)
        store["plugins"] = [entry for entry in store["plugins"] if entry["package"] != RUNTIME_ID] + [meta]
        write_json(out / MANIFEST, validate_store(store))
        replace_asset(gh, rid, out / MANIFEST, "Pocket Studio plugin catalog")
        gh.api(f"releases/{rid}", method="PATCH", body={"draft": False, "make_latest": "false"})
        for item in gh.api(f"releases/{rid}")["assets"]:
            if item["name"].startswith(("previous-", "stage-")) and item["name"].endswith(("-" + RUNTIME, "-" + MANIFEST)):
                gh.remove(item)
    if plan["channel"] != "app":
        return
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
        body = f"Pocket Studio {plan['appVersion']} with Codex {meta['version']}.\n\n{marker}\n<!-- pocketstudio-app-sequence {seq} -->"
        release = gh.create(tag, "Pocket Studio " + tag, body)
    rid = release["id"]
    replace_asset(gh, rid, out / APP, f"Pocket Studio {plan['appVersion']} ({code})")
    replace_asset(gh, rid, out / RUNTIME, runtime_label(meta))
    for item in gh.api(f"releases/{rid}")["assets"]:
        if item["name"].startswith(("previous-", "stage-")) and item["name"].endswith(("-" + APP, "-" + RUNTIME)):
            gh.remove(item)
    newer = any(not r["draft"] and re.fullmatch("v" + SEMVER, r["tag_name"])
                and semver(r["tag_name"][1:]) > semver(tag[1:]) for r in releases)
    gh.api(f"releases/{rid}", method="PATCH", body={"draft": False, "make_latest": "false" if newer else "true"})


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
