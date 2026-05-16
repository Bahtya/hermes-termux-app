#!/usr/bin/env python3
"""
Hermux Device Control MCP Server

Exposes device UI automation and ADB control as MCP tools over stdio JSON-RPC.
Communicates with the Android-side HTTP server at localhost:18720.

Usage: python3 device_control.py
"""

import json
import sys
import urllib.request
import urllib.error

SERVER = "http://localhost:18720"

# ---------------------------------------------------------------------------
# MCP protocol helpers
# ---------------------------------------------------------------------------

def send_result(result):
    """Send a JSON-RPC response to stdout."""
    msg = json.dumps(result)
    sys.stdout.write(msg + "\n")
    sys.stdout.flush()


def make_error(req_id, code, message):
    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": code, "message": message},
    }


def make_response(req_id, result):
    return {"jsonrpc": "2.0", "id": req_id, "result": result}


# ---------------------------------------------------------------------------
# HTTP helpers
# ---------------------------------------------------------------------------

def http_get(path):
    try:
        req = urllib.request.Request(SERVER + path)
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.URLError:
        return {"ok": False, "error": "device control server not reachable (is accessibility service enabled?)"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def http_post(path, body):
    try:
        data = json.dumps(body).encode()
        req = urllib.request.Request(SERVER + path, data=data,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.URLError:
        return {"ok": False, "error": "device control server not reachable"}
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ---------------------------------------------------------------------------
# Tool definitions
# ---------------------------------------------------------------------------

TOOLS = [
    {
        "name": "click",
        "description": "Click a UI element on the device. Use one of: text (visible button/label text), id (Android resource ID), or x/y coordinates.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Visible text of the element to click"},
                "id": {"type": "string", "description": "Android view resource ID (e.g. com.app:id/button)"},
                "x": {"type": "integer", "description": "X coordinate for tap"},
                "y": {"type": "integer", "description": "Y coordinate for tap"},
            },
        },
    },
    {
        "name": "type_text",
        "description": "Type text into the currently focused input field. Supports Chinese and all Unicode characters.",
        "inputSchema": {
            "type": "object",
            "required": ["text"],
            "properties": {
                "text": {"type": "string", "description": "Text to type"},
            },
        },
    },
    {
        "name": "swipe",
        "description": "Perform a swipe gesture on the device screen.",
        "inputSchema": {
            "type": "object",
            "required": ["x1", "y1", "x2", "y2"],
            "properties": {
                "x1": {"type": "integer", "description": "Start X coordinate"},
                "y1": {"type": "integer", "description": "Start Y coordinate"},
                "x2": {"type": "integer", "description": "End X coordinate"},
                "y2": {"type": "integer", "description": "End Y coordinate"},
                "duration": {"type": "integer", "description": "Duration in ms (default 300)"},
            },
        },
    },
    {
        "name": "press_key",
        "description": "Press a global key: back, home, recents, notifications, quick_settings, power_dialog, lock_screen, take_screenshot.",
        "inputSchema": {
            "type": "object",
            "required": ["key"],
            "properties": {
                "key": {
                    "type": "string",
                    "enum": ["back", "home", "recents", "notifications",
                             "quick_settings", "power_dialog", "lock_screen", "take_screenshot"],
                    "description": "The key to press",
                },
            },
        },
    },
    {
        "name": "scroll",
        "description": "Scroll the current screen forward or backward.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "enum": ["forward", "backward"],
                    "default": "forward",
                    "description": "Scroll direction",
                },
            },
        },
    },
    {
        "name": "dump_ui",
        "description": "Dump the current screen UI hierarchy as a JSON tree. Each node has class, text, viewId, bounds, clickable, scrollable, editable, and children.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "get_current_app",
        "description": "Get the package name and Activity of the current foreground app.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "adb_shell",
        "description": "Execute an ADB shell command on the device. Requires ADB over TCP to be connected first. Used for system-level operations like settings, package management, etc.",
        "inputSchema": {
            "type": "object",
            "required": ["command"],
            "properties": {
                "command": {"type": "string", "description": "ADB shell command to execute"},
            },
        },
    },
    {
        "name": "adb_connect",
        "description": "Connect ADB over TCP to the device itself. For Android 11+ wireless debugging, use the port shown in Settings > Developer Options > Wireless Debugging.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "host": {"type": "string", "default": "localhost"},
                "port": {"type": "integer", "default": 5555},
            },
        },
    },
    {
        "name": "device_status",
        "description": "Check device control server status — whether accessibility service and ADB are connected.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]

# ---------------------------------------------------------------------------
# Tool execution
# ---------------------------------------------------------------------------

def call_tool(name, arguments):
    """Dispatch a tool call to the HTTP server."""
    if name == "click":
        return http_post("/click", arguments)
    elif name == "type_text":
        return http_post("/type", arguments)
    elif name == "swipe":
        return http_post("/swipe", arguments)
    elif name == "press_key":
        return http_post("/key", arguments)
    elif name == "scroll":
        return http_post("/scroll", arguments)
    elif name == "dump_ui":
        return http_get("/ui")
    elif name == "get_current_app":
        return http_get("/current_app")
    elif name == "adb_shell":
        return http_post("/adb/shell", {"command": arguments.get("command", "")})
    elif name == "adb_connect":
        return http_post("/adb/connect", {
            "host": arguments.get("host", "localhost"),
            "port": arguments.get("port", 5555),
        })
    elif name == "device_status":
        return http_get("/status")
    else:
        return {"ok": False, "error": f"Unknown tool: {name}"}


# ---------------------------------------------------------------------------
# JSON-RPC message handler
# ---------------------------------------------------------------------------

def handle_request(request):
    req_id = request.get("id")
    method = request.get("method")
    params = request.get("params", {})

    if method == "initialize":
        return make_response(req_id, {
            "protocolVersion": "2024-11-05",
            "capabilities": {"tools": {}},
            "serverInfo": {
                "name": "hermux-device-control",
                "version": "1.0.0",
            },
        })

    elif method == "notifications/initialized":
        # Client ack — no response needed
        return None

    elif method == "tools/list":
        return make_response(req_id, {"tools": TOOLS})

    elif method == "tools/call":
        tool_name = params.get("name", "")
        arguments = params.get("arguments", {})
        result = call_tool(tool_name, arguments)

        # Format as MCP tool result
        content = []
        if result.get("ok"):
            # For dump_ui, include the UI tree as text
            if "ui" in result:
                content.append({"type": "text", "text": json.dumps(result["ui"], ensure_ascii=False, indent=2)})
            elif "output" in result:
                content.append({"type": "text", "text": result["output"]})
            elif "app" in result:
                content.append({"type": "text", "text": json.dumps(result["app"], ensure_ascii=False, indent=2)})
            else:
                content.append({"type": "text", "text": result.get("message", "ok")})
        else:
            content.append({"type": "text", "text": "Error: " + result.get("error", "unknown error")})

        return make_response(req_id, {"content": content})

    elif method == "shutdown":
        return make_response(req_id, {})

    else:
        return make_error(req_id, -32601, f"Method not found: {method}")


# ---------------------------------------------------------------------------
# Main loop — read JSON-RPC from stdin, write responses to stdout
# ---------------------------------------------------------------------------

def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            continue

        response = handle_request(request)
        if response is not None:
            send_result(response)

        if request.get("method") == "shutdown":
            break


if __name__ == "__main__":
    main()
