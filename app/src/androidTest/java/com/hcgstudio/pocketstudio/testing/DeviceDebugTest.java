package com.hcgstudio.pocketstudio.testing;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.hcgstudio.pocketstudio.adb.Target;
import com.hcgstudio.pocketstudio.wireless.LocalWirelessAdb;
import com.hcgstudio.pocketstudio.AndroidEnvironments;
import com.hcgstudio.pocketstudio.debug.JdwpSession;

/** The host app debugs the fixture on the selected disposable emulator. */
public final class DeviceDebugTest extends Instrumentation {
    private static final String TARGET_CLASS = "dev.ondevice.studio.debuggee.BreakpointActivity";
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (java.util.Arrays.asList("codexDownload", "codexHandshake", "codexNetwork", "codexVerifyRelease", "codexOfflineInstall").contains(arguments.getString("scenario"))) {
                result.putString("result", com.hcgstudio.pocketstudio.CodexRuntimeChecks.run(getTargetContext(), getContext(), arguments.getString("scenario")));
            } else if ("pluginDiscovery".equals(arguments.getString("scenario"))) {
                result.putString("result", com.hcgstudio.pocketstudio.PluginDiscoveryChecks.run(getTargetContext(),
                        Boolean.parseBoolean(arguments.getString("legacy", "false")),
                        Boolean.parseBoolean(arguments.getString("trusted", "true"))));
            } else if ("androidEnvironment".equals(arguments.getString("scenario"))) {
                result.putString("result", com.hcgstudio.pocketstudio.PluginDiscoveryChecks.androidEnvironment(
                        getTargetContext(), Boolean.parseBoolean(arguments.getString("installed", "true"))));
            } else if ("pluginCatalog".equals(arguments.getString("scenario"))) {
                result.putString("result", com.hcgstudio.pocketstudio.PluginStoreChecks.catalog(getTargetContext()));
            } else if ("pluginLocalInstall".equals(arguments.getString("scenario"))) {
                result.putString("result", com.hcgstudio.pocketstudio.PluginStoreChecks.installLocally(getTargetContext(),
                        Integer.parseInt(arguments.getString("port")),
                        Boolean.parseBoolean(arguments.getString("androidEnvironment", "false"))));
            } else if ("apkOpen".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.ApkInstallerChecks.run(getTargetContext());
                result.putString("result", "PASS APK snapshots and provider scope");
            } else if ("appUpdates".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.AppReleaseChecks.run(getTargetContext(), arguments.getString("verifyArchive", "false").equals("true"));
                com.hcgstudio.pocketstudio.AppUpdateFlowChecks.run(getTargetContext());
                result.putString("result", "PASS app release selection, verification and update flow");
            } else if ("settings".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.SettingsChecks.run(getTargetContext());
                result.putString("result", "PASS settings persistence, scheme overrides and save actions");
            } else if ("editor".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.EditorChecks.run();
                result.putString("result", "PASS editor and refactoring");
            } else if ("codexTools".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.ProjectSearchChecks.run(getTargetContext());
                com.hcgstudio.pocketstudio.DeviceAgentChecks.run();
                result.putString("result", "PASS project search and device tool checks");
            } else if ("codexDeviceTools".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.DeviceAgentChecks.runConnected(getTargetContext(),
                        Integer.parseInt(arguments.getString("port")));
                result.putString("result", "PASS connected Logcat and device UI inspection");
            } else if ("codexAuth".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.codexplugin.CodexAuthChecks.run(getTargetContext(), getContext());
                result.putString("result", "PASS isolated Codex account credentials");
            } else if ("codexChat".equals(arguments.getString("scenario"))) {
                result.putString("result", com.hcgstudio.pocketstudio.CodexChatChecks.run(getTargetContext(),
                        Integer.parseInt(arguments.getString("port")), "true".equals(arguments.getString("keep"))));
            } else if ("gitSsh".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.GitRemoteSshChecks.run(getTargetContext(), arguments.getString("url"));
                result.putString("result", "PASS SSH clone, key authentication and host verification");
            } else if ("gitWorkspace".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.GitWorkspaceChecks.run(getTargetContext(), arguments.getString("tree"));
                result.putString("result", "PASS Git staging, commits, checkout, merge abort and workspace persistence");
            } else if ("workspace".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.ProjectWorkspaceChecks.run(getTargetContext(), arguments.getString("tree"));
                result.putString("result", "PASS selected project reads, writes, deletes, build artifacts and conflict recovery");
            } else if ("createProject".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.ProjectCreationChecks.run(getTargetContext(), arguments.getString("tree"));
                result.putString("result", "PASS create, reopen and build Android project");
            } else if ("gradle".equals(arguments.getString("scenario")) || "gradleAndroid".equals(arguments.getString("scenario"))) {
                boolean androidTemplate = "gradleAndroid".equals(arguments.getString("scenario"));
                com.hcgstudio.pocketstudio.LocalGradleChecks.run(getTargetContext(), androidTemplate, arguments.getString("tree"),
                        arguments.getString("gradleTasks", ":app:assembleDebug"));
                String message = "PASS local Gradle tasks, APK, failure and cancellation";
                if (arguments.getString("tree") != null) message = "PASS two Gradle builds, generated files, reports and deletions in the selected folder";
                if (androidTemplate) message = "PASS local Gradle Android APK";
                result.putString("result", message);
            } else if ("debugRun".equals(arguments.getString("scenario"))) {
                com.hcgstudio.pocketstudio.DebugRunChecks.run(this, arguments.getString("port"),
                        Integer.parseInt(arguments.getString("breakLine")));
                result.putString("result", "PASS offline breakpoint, debug launch and session notification actions");
            } else if ("build".equals(arguments.getString("scenario"))) {
                EmbeddedBuilderSmoke.run(getTargetContext());
                com.hcgstudio.pocketstudio.ProjectCreationChecks.installedCompileSdk(getTargetContext());
                result.putString("result", "PASS embedded build");
            } else {
                runDebugSession();
                result.putString("result", "PASS " + ("local".equals(arguments.getString("scenario")) ? "same-device" : "cross-device")
                        + " install, logcat, JDWP list, breakpoint, stack, locals, step, resume, detach");
            }
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("failure", Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runDebugSession() throws Exception {
        int port = Integer.parseInt(arguments.getString("port"));
        int line = Integer.parseInt(arguments.getString("breakLine"));
        int nextLine = Integer.parseInt(arguments.getString("stepLine"));
        Target connection;
        if ("usb".equals(arguments.getString("scenario"))) {
            connection = UsbGadgetTarget.connect(getTargetContext());
        } else if ("local".equals(arguments.getString("scenario"))) {
            int pairingPort = Integer.parseInt(arguments.getString("pairPort"));
            LocalWirelessAdb.pair(getTargetContext(), arguments.getString("code"), pairingPort);
            try (Target first = LocalWirelessAdb.connect(getTargetContext(), port)) {
                String hostPid = first.shell("pidof " + getTargetContext().getPackageName()).trim();
                check(hostPid.equals(String.valueOf(android.os.Process.myPid())), "connected to the Studio host");
            }
            connection = LocalWirelessAdb.connect(getTargetContext(), port);
        } else if ("wireless".equals(arguments.getString("scenario"))) {
            int pairingPort = Integer.parseInt(arguments.getString("pairPort"));
            AndroidEnvironments.require(getTargetContext()).pairRemote("10.0.2.2", pairingPort, arguments.getString("code"));
            connection = AndroidEnvironments.require(getTargetContext()).connectRemote("10.0.2.2", port, false);
        } else {
            connection = AndroidEnvironments.require(getTargetContext()).connectRemote("10.0.2.2", port, true);
        }
        try (Target adb = connection) {
            // The runner must point only to the disposable emulator named by the caller.
            check("1".equals(adb.shell("getprop ro.kernel.qemu").trim()), "expected an emulator target");
            Log.i("StudioWireTest", "Installing debuggee");
            File file = new File(getTargetContext().getFilesDir(), "studio-test-target.apk");
            try (FileInputStream apk = new FileInputStream(file)) {
                adb.install(apk, file.length());
            }
            check(adb.shell("pm path dev.ondevice.studio.debuggee").contains("package:"), "debuggee installation");
            Log.i("StudioWireTest", "Reading logcat");
            String marker = "studio-" + System.nanoTime();
            adb.shell("log -t StudioWireTest " + marker);
            check(adb.shell("logcat -d -s StudioWireTest:I").contains(marker), "remote logcat");

            adb.shell("input keyevent KEYCODE_WAKEUP");
            adb.shell("wm dismiss-keyguard");
            adb.shell("am force-stop dev.ondevice.studio.debuggee");
            Log.i("StudioWireTest", "Launching target");
            String launch = adb.shell("am start -W -n dev.ondevice.studio.debuggee/.BreakpointActivity");
            check(launch.contains("Status: ok"), "activity launch: " + launch);
            String pid = adb.shell("pidof dev.ondevice.studio.debuggee").trim();
            check(pid.matches("[1-9][0-9]*"), "fixture pid: " + pid);
            Log.i("StudioWireTest", "Listing processes");
            try (Target.Stream tracker = adb.open("track-jdwp")) {
                DataInputStream in = new DataInputStream(tracker.input());
                byte[] header = new byte[4];
                in.readFully(header);
                byte[] body = new byte[Integer.parseInt(new String(header, StandardCharsets.US_ASCII), 16)];
                in.readFully(body);
                String processes = "\n" + new String(body, StandardCharsets.US_ASCII).trim() + "\n";
                check(processes.contains("\n" + pid + "\n"), "process missing from track-jdwp: " + processes);
            }

            int[] center = buttonCenter(hierarchy(adb));
            BlockingQueue<Long> stops = new ArrayBlockingQueue<>(8);
            JdwpSession.Listener listener = new JdwpSession.Listener() {
                @Override public void onStopped(long thread, String reason) { stops.offer(thread); }
                @Override public void onDisconnected(String reason) { Log.i("StudioWireTest", reason); }
            };
            Log.i("StudioWireTest", "Attaching");
            try (Target.Stream stream = adb.open("jdwp:" + pid);
                 JdwpSession debugger = JdwpSession.attach(stream.input(), stream.output(), stream, listener)) {
                check(debugger.loadedClasses().contains(TARGET_CLASS), "ART loaded classes");
                debugger.setBreakpoint(TARGET_CLASS, line);
                try (Target.Stream tap = adb.open("shell:input tap " + center[0] + " " + center[1])) {
                    long thread = stop(stops);
                    List<String> frames = debugger.stack(thread);
                    check(!frames.isEmpty() && frames.get(0).startsWith(TARGET_CLASS + "$1.onClick:")
                            && frames.get(0).endsWith(":" + line), "breakpoint stack: " + frames);
                    Log.i("StudioWireTest", "Breakpoint: " + frames.get(0));
                    List<String> locals = debugger.locals(thread);
                    check(locals.stream().anyMatch(row -> row.startsWith("view = android.widget.Button@")),
                            "ART callback parameter: " + locals);
                    Log.i("StudioWireTest", "Variables: " + locals);
                    debugger.removeBreakpoint(TARGET_CLASS, line);
                    debugger.stepOver(thread);
                    List<String> stepped = debugger.stack(stop(stops));
                    check(stepped.get(0).endsWith(":" + nextLine), "step stack: " + stepped);
                    Log.i("StudioWireTest", "Step: " + stepped.get(0));
                    debugger.resume();
                }
            }
            check(hierarchy(adb).toLowerCase(Locale.ROOT).contains("text=\"hits 1\""), "target did not resume");
        }
    }

    private static String hierarchy(Target adb) throws IOException {
        String path = "/data/local/tmp/studio-window-" + System.nanoTime() + ".xml";
        try {
            adb.shell("uiautomator dump " + path);
            return adb.shell("cat " + path);
        } finally {
            adb.shell("rm -f " + path);
        }
    }

    private static int[] buttonCenter(String xml) throws IOException {
        Matcher match = Pattern.compile("<node [^>]*class=\"android\\.widget\\.Button\"[^>]*bounds=\""
                + "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(xml);
        if (!match.find()) throw new IOException("debuggee button missing from target window");
        int x = (Integer.parseInt(match.group(1)) + Integer.parseInt(match.group(3))) / 2;
        int y = (Integer.parseInt(match.group(2)) + Integer.parseInt(match.group(4))) / 2;
        return new int[]{x, y};
    }

    private static long stop(BlockingQueue<Long> stops) throws InterruptedException {
        Long thread = stops.poll(15, TimeUnit.SECONDS);
        check(thread != null && thread != 0, "timed out waiting for an ART debug event");
        return thread;
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
}
