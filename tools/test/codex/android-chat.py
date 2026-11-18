#!/usr/bin/env python3
"""Run the Android client against the local Responses fixture with an installed device runtime."""
import argparse
from http.server import ThreadingHTTPServer
import json
import subprocess
from threading import Thread
from protocol import Responses, check_turn_settings, digest


class AccountModelsAndResponses(Responses):
    def do_POST(self):
        if self.headers.get("Authorization") != "Bearer local-fixture-only":
            self.send_error(401)
            return
        super().do_POST()

    def do_GET(self):
        if self.path != "/v1/models":
            self.send_error(404)
            return
        if self.headers.get("Authorization") != "Bearer local-fixture-only":
            self.send_error(401)
            return
        self.server.catalog_requests += 1
        if self.server.catalog_requests == 1:
            self.send_error(503)
            return
        body = json.dumps({"object": "list", "data": [
            {"id": model, "object": "model"} for model in ("gpt-5.5", "my-deployment", "gpt-6.2-sol")
        ]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--keep", action="store_true", help="Keep this example project and chat in the emulator")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    if args.keep and not args.serial.startswith("emulator-"):
        parser.error("--keep is only available on a disposable emulator")
    markdown = "### 修改完成\n\n已将 `sample.txt` 的内容从 **one** 改为 **two**。\n\n```text\ntwo\n```"
    with ThreadingHTTPServer(("127.0.0.1", 0), AccountModelsAndResponses) as fixture:
        fixture.requests = []
        fixture.catalog_requests = 0
        fixture.code_mode = False
        fixture.markdown = markdown
        Thread(target=fixture.serve_forever, daemon=True).start()
        port = subprocess.check_output(adb + ["reverse", "tcp:0", f"tcp:{fixture.server_port}"], text=True).strip()
        try:
            command = adb + ["shell", "am", "instrument", "-w", "-e", "scenario", "codexChat",
                             "-e", "port", port, "-e", "keep", str(args.keep).lower(),
                             "com.hcgstudio.pocketstudio.test/com.hcgstudio.pocketstudio.testing.DeviceDebugTest"]
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=255)
            print(result.stdout, flush=True)
            if result.returncode or "INSTRUMENTATION_CODE: -1" not in result.stdout:
                raise RuntimeError("Android instrumentation failed")
            assert fixture.catalog_requests >= 2, "Catalog retry did not reach the fixture"
            check_turn_settings(fixture.requests)
            outputs = [[item for item in req["input"] if item["type"].endswith("_output")] for req in fixture.requests]
            assert digest("one") in str(outputs[1]), "The runtime did not receive the app's read_file result"
            assert digest("two") in str(outputs[2]), "The runtime did not receive the app's write_file result"
            print("PASS account catalog retry, selected model, effort and Fast on both turns, and both project-tool outputs")
        finally:
            subprocess.run(adb + ["reverse", "--remove", f"tcp:{port}"], check=True)
            fixture.shutdown()


if __name__ == "__main__":
    main()
