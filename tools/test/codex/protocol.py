#!/usr/bin/env python3
"""Exercise Pocket Studio's app-server protocol against a loopback Responses fixture.

Run with an installed Codex binary, e.g. python3 tools/test/codex/protocol.py --codex codex.
No credentials are used. Session files and the fixture project live in a temporary folder.
"""
import argparse
from collections import deque
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
from queue import Queue
import shutil
import subprocess
from tempfile import TemporaryDirectory
import threading
import time


def messages(item, index, model):
    response_id = f"resp_{index}"
    response = {"id": response_id, "object": "response", "model": model,
                "status": "in_progress", "output": []}
    events = [{"type": "response.created", "response": response},
              {"type": "response.output_item.added", "output_index": 0, "item": item}]
    if item["type"] == "message":
        events.append({"type": "response.output_text.delta", "item_id": item["id"],
                       "output_index": 0, "content_index": 0, "delta": item["content"][0]["text"]})
    events.extend([{"type": "response.output_item.done", "output_index": 0, "item": item},
                   {"type": "response.completed", "response": dict(response, output=[item], status="completed",
                     usage={"input_tokens": 10, "output_tokens": 2, "total_tokens": 12})}])
    return "".join("event: " + e["type"] + "\ndata: " + json.dumps(e) + "\n\n" for e in events).encode()


class Responses(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def tool_call(self, index, request):
        if index == 1:
            return "read_file", {"path": "sample.txt"}
        if index == 2:
            return "write_file", {"path": "sample.txt", "revision": digest("one"), "text": "two"}
        return None, None

    def do_POST(self):
        if self.path != "/v1/responses":
            self.send_error(404)
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.requests.append(request)
        index = len(self.server.requests)
        name, args = self.tool_call(index, request)
        if name and self.server.code_mode:
            item = {"id": f"ct_{index}", "type": "custom_tool_call", "namespace": "functions", "name": "exec",
                    "call_id": f"call_{index}", "input": f"text(await tools.studio__{name}({json.dumps(args)}))"}
        elif name:
            item = {"id": f"fc_{index}", "type": "function_call", "namespace": "studio", "name": name,
                    "call_id": f"call_{index}", "arguments": json.dumps(args)}
        else:
            item = {"type": "message", "id": f"msg_{index}", "role": "assistant", "status": "completed",
                    "content": [{"type": "output_text", "text": getattr(self.server, "markdown", "### Done"), "annotations": []}]}
        body = messages(item, index, request["model"])
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        if index == 3:
            self.send_header("x-codex-primary-used-percent", "32.5")
            self.send_header("x-codex-primary-window-minutes", "300")
            self.send_header("x-codex-primary-reset-at", str(int(time.time()) + 900))
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def digest(content):
    return hashlib.sha256(content.encode()).hexdigest()


def check_turn_settings(requests):
    # Switching models can add an internal compaction request before the resumed user turn.
    assert len(requests) >= 4, f"Resumed turn missing from {len(requests)} Responses requests"
    for body in requests[:3]:
        assert body["model"] == "gpt-6.2-sol" and body["reasoning"]["effort"] == "high"
        assert body.get("service_tier") == "priority", "Fast setting did not reach Responses"
    last = requests[-1]
    assert last["model"] == "gpt-5.5" and last["reasoning"]["effort"] == "low"
    assert last.get("service_tier") in (None, "default"), "Resumed chat retained the previous Fast tier"


class Rpc:
    def __init__(self, process):
        self.process = process
        self.queue = Queue()
        self.inbox = deque()
        self.next_id = 0
        threading.Thread(target=self.read_loop, daemon=True).start()

    def read_loop(self):
        for line in self.process.stdout:
            self.queue.put(json.loads(line))
        self.queue.put(None)

    def read(self):
        message = self.queue.get(timeout=25)
        if message is None:
            raise RuntimeError("Premature app-server exit")
        return message

    def send(self, message):
        self.process.stdin.write(json.dumps(message) + "\n")
        self.process.stdin.flush()

    def call(self, method, **params):
        self.next_id += 1
        self.send({"id": self.next_id, "method": method, "params": params})
        while True:
            message = self.read()
            if "method" in message or message.get("id") != self.next_id:
                self.inbox.append(message)
                continue
            if "error" in message:
                raise RuntimeError(f"{method}: {message['error']}")
            return message["result"]

    def event(self):
        return self.inbox.popleft() if self.inbox else self.read()


@contextmanager
def session(binary, home, port, authenticate, code_mode):
    provider = "chatgpt_plan"
    options = {"model_provider": provider,
               f"model_providers.{provider}.name": "ChatGPT plan",
               f"model_providers.{provider}.base_url": f"http://127.0.0.1:{port}/v1",
               f"model_providers.{provider}.env_key": "ACCESS_TOKEN",
               f"model_providers.{provider}.wire_api": "responses",
               f"model_providers.{provider}.supports_websockets": False,
               f"model_providers.{provider}.requires_openai_auth": False,
               "features.shell_tool": False, "features.incremental_tools": False,
               "shell_environment_policy.exclude": ["ACCESS_TOKEN", "OPENAI_API_KEY"]}
    if not code_mode:
        options["features.code_mode.direct_only_tool_namespaces"] = ["studio"]
    cmd = [binary]
    if not Path(binary).name.startswith("codex-app-server"):
        cmd.append("app-server")
    cmd.extend(["--listen", "stdio://"])
    for key, value in options.items():
        cmd += ["-c", f"{key}={json.dumps(value)}"]
    # This environment does not inherit any user credentials, plugin settings or shell init.
    env = {"CODEX_HOME": str(home), "HOME": str(home), "PATH": os.defpath,
           "TMPDIR": str(home / "tmp"), "LANG": "C.UTF-8"}
    if authenticate:
        env["ACCESS_TOKEN"] = "local-fixture"
    (home / "tmp").mkdir(exist_ok=True)
    with (home / "server.log").open("a") as log:
        proc = subprocess.Popen(cmd, env=env, cwd=home, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=log, text=True)
        try:
            rpc = Rpc(proc)
            rpc.call("initialize", clientInfo={"name": "Pocket Studio", "title": "掌上 Studio", "version": "0.2.0"},
                     capabilities={"experimentalApi": True})
            rpc.send({"method": "initialized"})
            yield rpc
        finally:
            proc.stdin.close()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()


def project_tools():
    definitions = {"list_files": ["path"], "read_file": ["path"], "write_file": ["path", "revision", "text"],
                   "create_directory": ["path"], "run_gradle": ["tasks"]}
    tools = [{"type": "function", "name": name, "description": name, "deferLoading": False,
              "inputSchema": {"type": "object", "properties": {k: {"type": "string"} for k in args},
                              "required": args, "additionalProperties": False}}
             for name, args in definitions.items()]
    return [{"type": "namespace", "name": "studio", "description": "Selected Android project", "tools": tools}]


def exercise(binary, home, server):
    port = server.server_port
    thread_options = {"cwd": str(home), "model": "gpt-6.2-sol", "sandbox": "read-only",
                      "approvalPolicy": "on-request", "developerInstructions": "Use the studio tools."}
    seen = []
    final = {}
    quotas = []
    file = home / "sample.txt"
    file.write_text("one")
    with session(binary, home, port, True, server.code_mode) as client:
        thread = client.call("thread/start", **thread_options, dynamicTools=project_tools(), environments=[])["thread"]["id"]
        turn = client.call("turn/start", threadId=thread, model="gpt-6.2-sol", effort="high", serviceTier="priority",
                           input=[{"type": "text", "text": "Update the sample."}])["turn"]["id"]
        while True:
            event = client.event()
            method, params = event["method"], event.get("params", {})
            seen.append(method)
            if method == "item/tool/call":
                assert params["namespace"] == "studio"
                name, args = params["tool"], params["arguments"]
                assert args["path"] == "sample.txt" and name in ("read_file", "write_file")
                if name == "read_file":
                    result = json.dumps({"revision": digest(file.read_text()), "text": file.read_text()})
                else:
                    assert digest(file.read_text()) == args["revision"]
                    file.write_text(args["text"])
                    result = "File updated"
                client.send({"id": event["id"], "result": {"success": True, "contentItems": [
                    {"type": "inputText", "text": result}]}})
            elif method == "account/rateLimits/updated":
                quotas.append(params)
            elif method == "item/completed":
                item = params["item"]
                final[item["id"]] = item
            elif method == "turn/completed":
                assert params["turn"]["id"] == turn and params["turn"]["status"] == "completed", params["turn"]
                break
    if file.read_text() != "two":
        outputs = [i for body in server.requests for i in body["input"] if i["type"].endswith("_output")]
        print("Tool error outputs:", outputs)
        print("Tool catalog:", [(t["name"], [n["name"] for n in t.get("tools", [])]) for item in server.requests[0]["input"] if item["type"] == "additional_tools" for t in item["tools"]])
        raise AssertionError("Project tools did not edit the fixture file")
    assert any(i["type"] == "agentMessage" and i["text"] == "### Done" for i in final.values()), final
    assert "item/agentMessage/delta" in seen and len(server.requests) == 3, seen
    # A new server with no token must be able to show the history. Allow asynchronous indexing to finish.
    with session(binary, home, port, False, server.code_mode) as client:
        for _ in range(10):
            page = client.call("thread/list", sortKey="updated_at", sourceKinds=["appServer","cli","unknown","vscode"],
                               modelProviders=[], limit=50)
            match = next((t for t in page["data"] if t["id"] == thread), None)
            if match:
                break
            time.sleep(.1)
        assert match and (match.get("name") or match.get("preview")), page
        page = client.call("thread/turns/list", threadId=thread, sortDirection="asc", itemsView="full", limit=10)
        items = page["data"][0]["items"]
        assert any(i["type"] == "userMessage" for i in items)
        tools = [i for i in items if i["type"] == "dynamicToolCall"]
        assert [i["tool"] for i in tools] == ["read_file", "write_file"], items
        for item in tools:
            assert item["namespace"] == "studio" and isinstance(item["arguments"], dict)
            assert item["status"] == "completed" and item["contentItems"][0]["type"] == "inputText", item
    with session(binary, home, port, True, server.code_mode) as client:
        result = client.call("thread/resume", threadId=thread, excludeTurns=True, **dict(thread_options, model="gpt-5.5"))
        assert result["thread"]["id"] == thread
        turn = client.call("turn/start", threadId=thread, model="gpt-5.5", effort="low", serviceTier="default",
                           input=[{"type": "text", "text": "Confirm the change."}])["turn"]["id"]
        while True:
            event = client.event()
            if event["method"] == "turn/completed":
                finished = event["params"]["turn"]
                assert finished["id"] == turn and finished["status"] == "completed", finished
                break
    check_turn_settings(server.requests)

    unsupported = set("background conversation max_output_tokens max_tool_calls metadata moderation multi_agent prompt "
                      "prompt_cache_retention safety_identifier temperature top_logprobs top_p truncation user".split())
    print("Events:", ", ".join(sorted(set(seen))))
    for body in server.requests[:3] + server.requests[-1:]:
        assert body["store"] is False and body["stream"] is True
        assert not unsupported.intersection(body), sorted(unsupported.intersection(body))
        assert not any(i.get("role") == "system" for i in body["input"])
        catalogs = [i for i in body["input"] if i["type"] == "additional_tools"]
        declared = body.get("tools", []) + [t for c in catalogs for t in c["tools"]]
        assert all(t["type"] not in ("tool_search", "programmatic_tool_calling", "image_generation", "file_search",
                                     "code_interpreter", "computer", "mcp") for t in declared)
        if not server.code_mode:
            studio = next(t for t in declared if t.get("name") == "studio")
            assert {t["name"] for t in studio["tools"]} == {t["name"] for t in project_tools()[0]["tools"]}
    print("Responses keys:", ", ".join(sorted(server.requests[0])))
    windows = [q["rateLimits"]["primary"] for q in quotas]
    assert None in windows and any(w and w["usedPercent"] == 33 and w["windowDurationMins"] == 300 for w in windows)
    print("PASS project read/write, streamed reply, changed model, effort and speed on resume, plan quota present and absent.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--code-mode", action="store_true")
    args = parser.parse_args()
    binary = shutil.which(args.codex)
    if not binary:
        parser.error("Codex executable not found")
    with TemporaryDirectory(prefix="pocket-studio-rpc-") as directory, ThreadingHTTPServer(("127.0.0.1", 0), Responses) as server:
        server.requests = []
        server.code_mode = args.code_mode
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            exercise(binary, Path(directory), server)
        finally:
            server.shutdown()
