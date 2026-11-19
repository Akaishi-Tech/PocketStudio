#!/usr/bin/env python3
"""Run the Android client against the local Responses fixture with an installed device runtime."""
import argparse
import base64
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
NODE_TARGETS = {
    "click_device_node": ("text", "Activate"),
    "long_press_device_node": ("text", "Activated"),
    "type_device_node": ("content-desc", "Test input"),
    "scroll_device_node": ("content-desc", "Test list"),
}
NAVIGATION_TOOLS = ("press_device_back", "press_device_home", "press_device_recents")
DEVICE_TOOLS = [
    ("click_device_node", {}), ("inspect_device_ui", {}),
    ("long_press_device_node", {}), ("inspect_device_ui", {}),
    ("type_device_node", {"text": "Node42"}), ("inspect_device_ui", {}),
    ("scroll_device_node", {"direction": "down"}), ("inspect_device_ui", {}),
    ("capture_device_screen", {}),
] + [(name, {}) for name in NAVIGATION_TOOLS]


def output_text(content):
    return content if isinstance(content, str) else "\n".join(part["text"] for part in content)


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
        if called == len(self.server.tools):
            return None, None
        name, args = self.server.tools[called]
        if name in NODE_TARGETS:
            last_inspection = next(call for tool, call in reversed(self.server.resumed_calls) if tool == "inspect_device_ui")
            content = next(item["output"] for body in reversed(self.server.requests) for item in body["input"]
                           if item.get("call_id") == last_inspection and "output" in item)
            attribute, value = NODE_TARGETS[name]
            nodes = json.loads(output_text(content))["nodes"]
            node = next((node for node in nodes if node.get(attribute) == value), None)
            assert node is not None, (name, attribute, value, [n.get(attribute) for n in nodes])
            args = dict(args, ref=node["ref"])
        self.server.resumed_calls.append((name, f"call_{index}"))
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
    parser.add_argument("--device-port", type=int, help="Exercise layout actions and screenshots using this emulator's adbd TCP port")
    args = parser.parse_args()
    adb = ["adb", "-s", args.serial]
    if args.keep and not args.serial.startswith("emulator-"):
        parser.error("--keep is only available on a disposable emulator")
    if args.device_port is not None and (not args.serial.startswith("emulator-") or args.keep or not 1 <= args.device_port <= 65535):
        parser.error("--device-port requires a disposable emulator, a valid port, and a temporary project")
    markdown = "### 修改完成\n\n已将 `sample.txt` 的内容从 **one** 改为 **two**。\n\n```text\ntwo\n```"
    with ThreadingHTTPServer(("127.0.0.1", 0), AccountModelsAndResponses) as fixture:
        fixture.requests = []
        fixture.catalog_requests = 0
        fixture.code_mode = False
        fixture.markdown = markdown
        fixture.keep = args.keep
        fixture.tools = RESUMED_TOOLS + (DEVICE_TOOLS if args.device_port else [])
        fixture.resumed_calls = []
        Thread(target=fixture.serve_forever, daemon=True).start()
        port = subprocess.check_output(adb + ["reverse", "tcp:0", f"tcp:{fixture.server_port}"], text=True).strip()
        try:
            command = adb + ["shell", "am", "instrument", "-w", "-e", "scenario", "codexChat",
                             "-e", "port", port, "-e", "keep", str(args.keep).lower(), "-e", "devicePort", str(args.device_port or 0),
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
    expected = {name for name, _ in fixture.tools}
    assert [name for name, _ in fixture.resumed_calls] == [name for name, _ in fixture.tools]
    for request in (fixture.requests[0], fixture.requests[-1]):
        catalog = request.get("tools", []) + [tool for item in request["input"] if item["type"] == "additional_tools"
                                             for tool in item["tools"]]
        studio = next(tool for tool in catalog if tool.get("name") == "studio")
        assert expected.issubset({tool["name"] for tool in studio["tools"]}), studio
    returned = {item["call_id"]: item["output"] for batch in outputs for item in batch}

    calls = dict(fixture.resumed_calls)

    def result(name):
        return output_text(returned[calls[name]])

    search = json.loads(result("search_project"))
    assert search["matches"] == [{"path": "sample.txt", "line": 1, "excerpt": "two"}], search
    editor = json.loads(result("get_editor_context"))
    assert editor["path"] == "sample.txt" and editor["dirty"] and editor["selection"]["text"] == "selected", editor
    assert result("read_logcat").strip() and result("inspect_device_ui").strip()
    if "capture_device_screen" in expected:
        snapshots = [json.loads(output_text(returned[call]))["nodes"] for name, call in fixture.resumed_calls
                     if name == "inspect_device_ui"]
        initial, clicked, held, typed, scrolled = snapshots
        assert any(n.get("text") == "Activate" for n in initial)
        assert any(n.get("text") == "Activated" for n in clicked)
        assert any(n.get("text") == "Held" for n in held)
        assert any(n.get("content-desc") == "Test input" and "Node42" in n.get("text", "") for n in typed)
        def rows(snapshot):
            return {n["text"] for n in snapshot if n.get("text", "").startswith("Row ")}
        assert rows(scrolled) - rows(typed), "Scrolling the referenced list should reveal new rows"
        content = returned[calls["capture_device_screen"]]
        image, = [part for part in content if part["type"] == "input_image"]
        dimensions, = [json.loads(part["text"]) for part in content if part["type"] == "input_text"]
        for axis in ("Width", "Height"):
            assert 0 < dimensions["image" + axis] <= dimensions["screen" + axis]
        header, data = image["image_url"].split(",", 1)
        raw = base64.b64decode(data, validate=True)
        assert (header == "data:image/png;base64" and raw.startswith(b"\x89PNG\r\n\x1a\n")
                or header == "data:image/jpeg;base64" and raw.startswith(b"\xff\xd8") and raw.endswith(b"\xff\xd9"))
        for name in NAVIGATION_TOOLS:
            assert json.loads(result(name)) == {"action": "key", "ok": True}, name
        print("PASS model received the screenshot and used layout references to click, long-press, type, and scroll")
    print("PASS IDE tools registered and called on resume, saved-file search and live unsaved selection")


if __name__ == "__main__":
    main()
