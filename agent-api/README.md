# Agent plugins

An agent plugin is a signed Android APK implementing the Kotlin interfaces in this module. Build against `:agent-api` with `compileOnly`. Pocket Studio supplies the API, Kotlin, coroutines and the Compose runtime when it opens the plugin. Give the provider a distinct Java package and a public, zero-argument entry class implementing `AgentPlugin`. [API declarations](src/main/java/com/hcgstudio/pocketstudio/agent/AgentPlugin.kt)

Register the marker service under the general plugin API with category `agent`. Add integer metadata `com.hcgstudio.pocketstudio.AGENT_API = 1` and string metadata `com.hcgstudio.pocketstudio.AGENT_ENTRY` containing the fully qualified entry class. Keep the earlier `com.hcgstudio.pocketstudio.AGENT_PLUGIN` intent action on the same marker to support older Studio hosts. Its label appears in the provider selector. Studio verifies both API versions and the installed APK's signing certificate before loading the entry point. The provider runs in Studio's process with its privileges. [Discovery API](../plugin-api/README.md) · [Codex manifest](../plugins/codex/apk/src/main/AndroidManifest.xml)

## Session lifecycle

Studio calls `AgentPlugin.open(host)` for the selected project and provider. The returned `AgentSession` exposes login and messaging through observable properties such as Compose snapshot state. The host invokes session methods on the main thread. Launch asynchronous work in `host.scope`, move blocking I/O to an IO dispatcher, and release processes, requests and notifications in `close()`. Studio refreshes on resume and installation changes and closes the session when its project or provider changes.

- Implement `AgentLogin` to expose supported login options, accounts, device codes, key configuration and usage. The host renders these values and supplies the browser-opening callback. The provider owns authorization, refresh, logout, credentials and cancellation. A browser login can acquire `host.keepLoginAlive()` until completion.
- Implement `AgentMessages` to list and reopen conversations, submit messages, cancel an active turn and publish incremental transcript updates. Use stable chat and line IDs. Report updated actions through `host.reportStatus(shortText)` while a turn runs and clear the notification with `reportStatus(null)` on every terminal path. Enable `offersModelSelection` when the provider exposes selectable models.
- Use `host.tools` to obtain tool names, descriptions and JSON schemas. Adapt them to the provider protocol and pass requests to `host.callTool(name, arguments)`. Studio handles the current project's document permissions, unsaved changes, device connection and user approval.
- Use `host.pluginContext` for the plugin's resources and read-only installed native libraries. Use provider-specific names under `host.context.noBackupFilesDir` for private data and `host.projectId` to partition project history.

The store publishes plugin artifacts and their verified metadata in `plugin-store.json`. The Codex provider and packaging recipe are a complete implementation. [Codex plugin](../plugins/codex/README.md)
