# Agent plugins

An agent plugin is a signed Android APK implementing the Kotlin interfaces in this module. Build against `:agent-api` with `compileOnly`. Pocket Studio supplies the API, Kotlin, coroutines and the Compose runtime when it opens the plugin. Give the provider a distinct Java package and a public, zero-argument entry class implementing `AgentPlugin`. [API declarations](src/main/java/com/hcgstudio/pocketstudio/agent/AgentPlugin.kt)

Register the marker service under the general plugin API with category `agent`. Set integer metadata `com.hcgstudio.pocketstudio.AGENT_API` to the API version the provider needs (2 for image results). Set `com.hcgstudio.pocketstudio.AGENT_ENTRY` to the fully qualified entry class. Keep the earlier `com.hcgstudio.pocketstudio.AGENT_PLUGIN` intent action on the same marker for discovery. Its label appears in the provider selector. Studio verifies both API versions and the installed APK's signing certificate before loading the entry point. The provider runs in Studio's process with its privileges. [Discovery API](../plugin-api/README.md) · [Codex manifest](../plugins/codex/apk/src/main/AndroidManifest.xml)

## Session lifecycle

Studio calls `AgentPlugin.open(host)` for the selected project and provider. The returned `AgentSession` exposes login and messaging through observable properties such as Compose snapshot state. The host invokes session methods on the main thread. Launch asynchronous work in `host.scope`, move blocking I/O to an IO dispatcher, and release processes, requests and notifications in `close()`. Studio refreshes on resume and installation changes and closes the session when its project or provider changes.

- Implement `AgentLogin` to expose supported login options, accounts, device codes, key configuration and usage. The host renders these values and supplies the browser-opening callback. The provider owns authorization, refresh, logout, credentials and cancellation. A browser login can acquire `host.keepLoginAlive()` until completion.
- Implement `AgentMessages` to list and reopen conversations, submit messages, cancel an active turn and publish incremental transcript updates. Use stable chat and line IDs. Report updated actions through `host.reportStatus(shortText)` while a turn runs and clear the notification with `reportStatus(null)` on every terminal path. Enable `offersModelSelection` when the provider exposes selectable models.
- API 2 hosts implement `AgentToolProvider`. Register the tool names, descriptions and JSON schemas from `availableTools` with your agent. Pass each call to `executeTool(name, arguments)` and forward its ordered results. `AgentToolContent.Text` carries text and `AgentToolContent.Image` carries encoded image bytes and a MIME type. Studio owns the tool definitions and their execution, including device input and screen capture.
- API 1 providers continue to use `host.tools` and `host.callTool(name, arguments)` for text results. Studio handles the current project's document permissions, unsaved changes, device connection and user approval.
- Use `host.pluginContext` for the plugin's resources and read-only installed native libraries. Use provider-specific names under `host.context.noBackupFilesDir` for private data and `host.projectId` to partition project history.

## Android computer use

Start with `inspect_device_ui({})` on the selected ADB target. Choose a control by its text, accessibility description or resource ID and pass its `ref` to an action. Device input, a new inspection or reconnecting invalidates the previous references. Fetch a fresh layout after acting or if a target has changed.

| Tool | Arguments | Effect |
| --- | --- | --- |
| `click_device_node` | `ref` | Tap the identified control. |
| `long_press_device_node` | `ref` | Touch and hold the control. |
| `type_device_node` | `ref`, `text` | Focus the editable control and type printable ASCII at the cursor or over its selection. |
| `scroll_device_node` | `ref`, `direction` | Scroll a container up, down, left or right. Down reveals content further down the page. |

The dedicated `press_device_back`, `press_device_home` and `press_device_recents` tools each take an empty object.

Use `host.executeTool("capture_device_screen", JSONObject())` for an explicit screenshot request or when the layout cannot identify a target. It returns a text item with `screenWidth`, `screenHeight`, `imageWidth` and `imageHeight`, followed by an image item with its MIME type. For a fallback tap or swipe through `perform_device_action`, multiply each image coordinate by the corresponding screen-to-image dimension ratio and round to an integer.

The store publishes plugin artifacts and their verified metadata in `plugin-store.json`. The Codex provider and packaging recipe are a complete implementation. [Codex plugin](../plugins/codex/README.md)
