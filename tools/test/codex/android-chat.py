#!/usr/bin/env python3
"""Run the Android client against the local Responses fixture with an installed device runtime."""
import argparse
from http.server import ThreadingHTTPServer
import json
import subprocess
from threading import Thread
from protocol import Responses, check_turn_settings, digest


RESUMED_TOOLS = [
    ("search_project", {"query": "two"}),
    ("get_editor_context", {}),
    ("read_logcat", {"packageName": "com.hcgstudio.pocketstudio", "maxLines": 20}),
    ("inspect_device_ui", {}),
]


class AccountModelsAndResponses(Responses):
    def tool_call(self, index, request):
        if index <= 3 or self.server.keep:
            return super().tool_call(index, request)
        # A change of model can trigger an internal request before the resumed user turn.
        if not self.server.resumed_calls and not any(
            item.get("role") == "user" and any(part.get("text") == "请确认修改已完成。" for part in item["content"])
            for item in request["input"]
        ):
            return None, None
        called = len(self.server.resumed_calls)
        if called == len(RESUMED_TOOLS):
            return None, None
        name, args = RESUMED_TOOLS[called]
        self.server.resumed_calls[name] = f"call_{index}"
        return name, args

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
        fixture.keep = args.keep
        fixture.resumed_calls = {}
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
            if not args.keep:
                check_resumed_tools(fixture, outputs)
        finally:
            subprocess.run(adb + ["reverse", "--remove", f"tcp:{port}"], check=True)
            fixture.shutdown()


def check_resumed_tools(fixture, outputs):
    expected = {name for name, _ in RESUMED_TOOLS}
    assert set(fixture.resumed_calls) == expected, fixture.resumed_calls
    for request in (fixture.requests[0], fixture.requests[-1]):
        catalog = request.get("tools", []) + [tool for item in request["input"] if item["type"] == "additional_tools"
                                             for tool in item["tools"]]
        studio = next(tool for tool in catalog if tool.get("name") == "studio")
        assert expected.issubset({tool["name"] for tool in studio["tools"]}), studio
    returned = {item["call_id"]: item["output"] for batch in outputs for item in batch}

    def result(name):
        content = returned[fixture.resumed_calls[name]]
        return content if isinstance(content, str) else "\n".join(part["text"] for part in content)

    search = json.loads(result("search_project"))
    assert search["matches"] == [{"path": "sample.txt", "line": 1, "excerpt": "two"}], search
    editor = json.loads(result("get_editor_context"))
    assert editor["path"] == "sample.txt" and editor["dirty"] and editor["selection"]["text"] == "selected", editor
    assert result("read_logcat").strip() and result("inspect_device_ui").strip()
    print("PASS four IDE tools registered and called on resume, saved-file search and live unsaved selection")


if __name__ == "__main__":
    main()
