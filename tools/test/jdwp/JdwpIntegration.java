import com.hcgstudio.pocketstudio.debug.JdwpSession;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Exercises real packets against a second JVM, including disposing a suspended VM. */
public final class JdwpIntegration {
    public static void main(String[] args) throws Exception {
        boolean hasVariables = args.length == 1;
        List<String> fixture = Files.readAllLines(Paths.get(args[0]));
        int breakLine = lineOf(fixture, "BREAK_HERE");
        int stepLine = lineOf(fixture, "STEP_HERE");
        Process target = new ProcessBuilder(System.getProperty("java.home") + "/bin/java",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
            "-cp", System.getProperty("java.class.path"), "BreakpointTarget")
            .redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(target.getInputStream()))) {
            String greeting = output.readLine();
            check(greeting != null && greeting.contains("address: "), "target JDWP listener: " + greeting);
            int port = Integer.parseInt(greeting.substring(greeting.lastIndexOf(' ') + 1));
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                BlockingQueue<Long> stops = new ArrayBlockingQueue<>(8);
                JdwpSession.Listener listener = new JdwpSession.Listener() {
                    @Override public void onStopped(long thread, String reason) { stops.add(thread); }
                    @Override public void onDisconnected(String reason) { System.err.println(reason); }
                };
                try (JdwpSession session = JdwpSession.attach(socket.getInputStream(),
                        socket.getOutputStream(), socket, listener)) {
                    stoppedThread(stops); // suspend=y sends VM_START even before IDSizes.
                    // The entry class has not yet been loaded. The watch must survive VM_START.
                    session.setBreakpoint("BreakpointTarget", breakLine);
                    session.setBreakpoint("BreakpointTarget", stepLine);
                    session.removeBreakpoint("BreakpointTarget", stepLine);
                    session.resume();
                    check("READY".equals(output.readLine()), "target fixture readiness");
                    check(session.loadedClasses().contains("BreakpointTarget"), "class not listed");
                    session.setBreakpoint("BreakpointTarget", breakLine); // idempotent after class prepare
                    target.getOutputStream().write(10);
                    target.getOutputStream().flush();
                    long thread = stoppedThread(stops);
                    List<String> stack = session.stack(thread);
                    check(stack.get(0).equals("BreakpointTarget.work:" + breakLine), stack.toString());
                    check(stack.get(1).startsWith("BreakpointTarget.main:"), stack.toString());
                    if (hasVariables) {
                        List<String> locals = session.locals(thread);
                        check(locals.contains("input = 20"), "argument lookup: " + locals);
                        check(locals.contains("text = \"test\\ninput\""), "string lookup: " + locals);
                        check(locals.contains("optional = null"), "null lookup: " + locals);
                        check(locals.stream().noneMatch(s -> s.startsWith("value = ")), "scope: " + locals);
                    } else {
                        try {
                            session.locals(thread);
                            throw new AssertionError("expected the stripped variable table to be reported");
                        } catch (IOException expected) {
                            check(expected.getMessage().equals(text("noVariableTable")), expected.toString());
                        }
                    }
                    session.removeBreakpoint("BreakpointTarget", breakLine);
                    session.stepOver(thread);
                    thread = stoppedThread(stops);
                    check(session.stack(thread).get(0).equals("BreakpointTarget.work:" + stepLine),
                        "single step did not reach the following line");
                    if (hasVariables) {
                        check(session.locals(thread).contains("value = 21"), "local value after stepping");
                    }
                    session.stepOver(thread);
                    stoppedThread(stops);
                    // The try-with-resources dispose must resume the suspended target.
                }
            }
            check(target.waitFor(5, TimeUnit.SECONDS), "target still suspended after detach");
            check(output.lines().anyMatch("RESULT 42"::equals), "fixture result");
        } finally {
            target.destroyForcibly();
        }
        System.out.println("PASS attach, VM start, classes, breakpoints, stack, repeated stepping, detach, "
                + (hasVariables ? "local values" : "stripped variable table"));
    }

    private static long stoppedThread(BlockingQueue<Long> stops) throws InterruptedException {
        Long value = stops.poll(8, TimeUnit.SECONDS);
        check(value != null && value != 0, "timed out waiting for debugger stop");
        return value;
    }

    private static int lineOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i + 1;
        throw new AssertionError(needle);
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }
}
