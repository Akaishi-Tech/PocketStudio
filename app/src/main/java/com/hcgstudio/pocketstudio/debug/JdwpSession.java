package com.hcgstudio.pocketstudio.debug;

import static com.hcgstudio.pocketstudio.protocol.ProtocolMessages.text;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Source debugger for the standard JDWP endpoint of an attached, debuggable process. */
public final class JdwpSession implements Closeable {
    public interface Listener {
        /** Runs on the event worker. The VM is suspended and its stack can be inspected. */
        void onStopped(long threadId, String reason);
        void onDisconnected(String reason);
    }

    private static final int SINGLE_STEP = 1, BREAKPOINT = 2, CLASS_PREPARE = 8, VM_START = 90, VM_DEATH = 99;
    private static final int SUSPEND_ALL = 2;
    private final Listener listener;
    private final JdwpWire wire;
    private final ExecutorService events = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "JDWP events");
        worker.setDaemon(true);
        return worker;
    });
    private final CountDownLatch sizesReady = new CountDownLatch(1);
    private final Map<String, SourceBreakpoint> breakpoints = new HashMap<>();
    private final Map<Integer, SourceBreakpoint> prepareRequests = new HashMap<>();

    private static final class SourceBreakpoint {
        final int line;
        final List<Integer> locations = new ArrayList<>(), watches = new ArrayList<>();
        final Set<Long> boundTypes = new HashSet<>();
        SourceBreakpoint(int line) { this.line = line; }
    }
    private int methodSize, objectSize, typeSize, frameSize;
    private int stepRequest;
    private volatile boolean closing;

    private JdwpSession(InputStream in, OutputStream out, Closeable transport, Listener listener) {
        this.listener = listener;
        wire = new JdwpWire(in, out, transport, new JdwpWire.Receiver() {
            @Override public void event(byte[] packet) {
                synchronized (events) {
                    if (!events.isShutdown()) events.execute(() -> handleEvent(packet));
                }
            }
            @Override public void disconnected(IOException cause) {
                synchronized (events) {
                    if (!closing) events.execute(() -> listener.onDisconnected(cause.getMessage()));
                    events.shutdown();
                }
            }
        });
    }

    /** Takes ownership of transport, including on handshake failure. Use off the UI thread. */
    public static JdwpSession attach(InputStream in, OutputStream out, Closeable transport,
                                     Listener listener) throws IOException {
        JdwpSession session = new JdwpSession(in, out, transport, listener);
        try {
            session.wire.start();
            DataInputStream sizes = session.wire.command(1, 7, JdwpWire.EMPTY);
            readSize(sizes); // fieldID
            session.methodSize = readSize(sizes);
            session.objectSize = readSize(sizes);
            session.typeSize = readSize(sizes);
            session.frameSize = readSize(sizes);
            return session;
        } catch (IOException e) {
            session.closing = true;
            session.wire.terminate(e);
            throw e;
        } finally {
            session.sizesReady.countDown();
        }
    }

    private static int readSize(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > 8) throw new IOException(text("jdwpIdSizeUnsupported"));
        return size;
    }

    /** Prepared non-array classes, in the binary-name form accepted by setBreakpoint. */
    public List<String> loadedClasses() throws IOException {
        TreeSet<String> names = new TreeSet<>();
        eachLoaded((tag, type, signature) -> names.add(typeName(signature)));
        return new ArrayList<>(names);
    }

    private interface ClassVisitor {
        void accept(int tag, long type, String signature) throws IOException;
    }

    private void eachLoaded(ClassVisitor visitor) throws IOException {
        DataInputStream reply = wire.command(1, 3, JdwpWire.EMPTY);
        int count = readCount(reply);
        for (int i = 0; i < count; i++) {
            int tag = reply.readUnsignedByte();
            long type = readId(reply, typeSize);
            String signature = readString(reply);
            int status = reply.readInt();
            if ((status & 2) != 0 && signature.startsWith("L")) visitor.accept(tag, type, signature);
        }
    }

    /** Watches future definitions first, then binds classes already prepared during attachment. */
    public synchronized void setBreakpoint(String binaryClassName, int line) throws IOException {
        String name = binaryClassName.trim();
        if (name.isEmpty() || name.indexOf('*') >= 0 || line <= 0) throw new IOException(text("classAndLineRequired"));
        String key = name + ":" + line;
        if (breakpoints.containsKey(key)) return;
        SourceBreakpoint point = new SourceBreakpoint(line);
        breakpoints.put(key, point);
        try {
            watchClass(name, point);
            watchClass(name + "$*", point);
            String exact = "L" + name.replace('.', '/');
            eachLoaded((tag, type, signature) -> {
                if (signature.equals(exact + ";") || signature.startsWith(exact + "$")) bind(point, tag, type);
            });
        } catch (IOException failure) {
            try { removeBreakpoint(name, line); }
            catch (IOException rollback) { wire.terminate(rollback); }
            throw failure;
        }
    }

    private void watchClass(String pattern, SourceBreakpoint point) throws IOException {
        int request = wire.command(15, 1, out -> {
            out.writeByte(CLASS_PREPARE);
            out.writeByte(SUSPEND_ALL);
            out.writeInt(1);
            out.writeByte(5); // ClassMatch
            writeString(out, pattern);
        }).readInt();
        prepareRequests.put(request, point);
        point.watches.add(request);
    }

    private void bind(SourceBreakpoint point, int tag, long type) throws IOException {
        if (point.boundTypes.add(type)) installAtLine(tag, type, point.line, point.locations);
    }

    private synchronized void classPrepared(int request, int tag, long type) throws IOException {
        SourceBreakpoint point = prepareRequests.get(request);
        if (point != null) bind(point, tag, type);
    }

    private void installAtLine(int tag, long type, int line, List<Integer> installed) throws IOException {
        for (MethodInfo method : methods(type)) {
            if ((method.modifiers & (0x0100 | 0x0400)) != 0) continue; // native or abstract
            for (Line entry : lineTable(type, method.id)) {
                if (entry.number == line) {
                    installed.add(installLocation(tag, type, method.id, entry.index));
                    break;
                }
            }
        }
    }

    private int installLocation(int tag, long type, long method, long index) throws IOException {
        return wire.command(15, 1, out -> {
            out.writeByte(BREAKPOINT);
            out.writeByte(SUSPEND_ALL);
            out.writeInt(1);
            out.writeByte(7); // LocationOnly
            out.writeByte(tag);
            writeId(out, type, typeSize);
            writeId(out, method, methodSize);
            out.writeLong(index);
        }).readInt();
    }

    public synchronized void removeBreakpoint(String binaryClassName, int line) throws IOException {
        String key = binaryClassName.trim() + ":" + line;
        SourceBreakpoint point = breakpoints.get(key);
        if (point == null) return;
        while (!point.watches.isEmpty()) {
            int last = point.watches.size() - 1;
            int request = point.watches.get(last);
            clearRequest(CLASS_PREPARE, request);
            point.watches.remove(last);
            prepareRequests.remove(request);
        }
        List<Integer> requests = point.locations;
        while (!requests.isEmpty()) {
            int last = requests.size() - 1;
            clearRequest(BREAKPOINT, requests.get(last));
            requests.remove(last);
        }
        breakpoints.remove(key);
    }

    private void clearRequest(int kind, int request) throws IOException {
        wire.command(15, 2, out -> {
            out.writeByte(kind);
            out.writeInt(request);
        });
    }

    public synchronized void resume() throws IOException {
        clearStep();
        wire.command(1, 9, JdwpWire.EMPTY);
    }

    /** Resumes the VM and suspends it again at the next source line in this frame or its caller. */
    public synchronized void stepOver(long threadId) throws IOException {
        clearStep();
        stepRequest = wire.command(15, 1, out -> {
            out.writeByte(SINGLE_STEP);
            out.writeByte(SUSPEND_ALL);
            out.writeInt(2);
            out.writeByte(10); // Step
            writeId(out, threadId, objectSize);
            out.writeInt(1); // LINE
            out.writeInt(1); // OVER
            out.writeByte(1); // Count, evaluated after the step filter
            out.writeInt(1);
        }).readInt();
        try {
            wire.command(1, 9, JdwpWire.EMPTY);
        } catch (IOException e) {
            clearStep();
            throw e;
        }
    }

    private void clearStep() throws IOException {
        if (stepRequest == 0) return;
        clearRequest(SINGLE_STEP, stepRequest);
        stepRequest = 0;
    }

    /** Returns up to 20 frames, innermost first. Requires a suspended thread. */
    public synchronized List<String> stack(long threadId) throws IOException {
        int total = wire.command(11, 7, out -> writeId(out, threadId, objectSize)).readInt();
        if (total <= 0) return Collections.emptyList();
        DataInputStream frames = wire.command(11, 6, out -> {
            writeId(out, threadId, objectSize);
            out.writeInt(0);
            out.writeInt(Math.min(total, 20));
        });
        int count = readCount(frames);
        List<String> result = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        Map<Long, List<MethodInfo>> methodsByType = new HashMap<>();
        for (int i = 0; i < count; i++) {
            readId(frames, frameSize);
            frames.readUnsignedByte();
            long type = readId(frames, typeSize);
            long method = readId(frames, methodSize);
            long index = frames.readLong();
            String className = names.get(type);
            if (className == null) {
                className = typeName(readString(wire.command(2, 1, out -> writeId(out, type, typeSize))));
                names.put(type, className);
                methodsByType.put(type, methods(type));
            }
            String methodName = "#" + Long.toHexString(method);
            for (MethodInfo candidate : methodsByType.get(type)) {
                if (candidate.id == method) {
                    methodName = candidate.name;
                    break;
                }
            }
            int line = -1;
            if (index >= 0) {
                for (Line entry : lineTable(type, method)) {
                    if (entry.index > index) break;
                    line = entry.number;
                }
            }
            result.add(className + "." + methodName + (line > 0 ? ":" + line : ""));
        }
        return result;
    }

    /** Reads live variables in the innermost frame without invoking code in the target. */
    public synchronized List<String> locals(long threadId) throws IOException {
        DataInputStream frame = wire.command(11, 6, out -> {
            writeId(out, threadId, objectSize);
            out.writeInt(0);
            out.writeInt(1);
        });
        if (readCount(frame) == 0) return Collections.emptyList();
        long frameId = readId(frame, frameSize);
        frame.readUnsignedByte();
        long type = readId(frame, typeSize);
        long method = readId(frame, methodSize);
        long index = frame.readLong();

        DataInputStream variables;
        try {
            variables = wire.command(6, 2, out -> {
                writeId(out, type, typeSize);
                writeId(out, method, methodSize);
            });
        } catch (JdwpWire.CommandError e) {
            if (e.code == 101 || e.code == 511) {
                throw new IOException(text("noVariableTable"), e);
            }
            throw e;
        }
        variables.readInt(); // argument word count
        int count = readCount(variables);
        List<Variable> visible = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = variables.readLong();
            String name = readString(variables);
            String signature = readString(variables);
            long length = variables.readInt() & 0xffffffffL;
            int slot = variables.readInt();
            if (index >= start && index - start < length && !signature.isEmpty()) {
                visible.add(new Variable(slot, name, signature.charAt(0)));
            }
        }
        if (visible.isEmpty()) return Collections.emptyList();
        DataInputStream values = wire.command(16, 1, out -> {
            writeId(out, threadId, objectSize);
            writeId(out, frameId, frameSize);
            out.writeInt(visible.size());
            for (Variable variable : visible) {
                out.writeInt(variable.slot);
                out.writeByte(variable.tag);
            }
        });
        if (readCount(values) != visible.size()) throw new IOException(text("variableCountMismatch"));
        List<String> rows = new ArrayList<>();
        for (Variable variable : visible) rows.add(variable.name + " = " + readValue(values));
        return rows;
    }

    private String readValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'B': return Byte.toString(in.readByte());
            case 'S': return Short.toString(in.readShort());
            case 'I': return Integer.toString(in.readInt());
            case 'J': return Long.toString(in.readLong());
            case 'Z': return Boolean.toString(in.readBoolean());
            case 'C': return "'" + escaped(Character.toString(in.readChar())).replace("'", "\\'") + "'";
            case 'F': return Float.toString(in.readFloat());
            case 'D': return Double.toString(in.readDouble());
            default:
                if ("[Lstglc".indexOf(tag) < 0) throw new IOException(text("valueTypeUnsupported", tag));
        }
        long object = readId(in, objectSize);
        if (object == 0) return "null";
        if (tag == 's') {
            String value = readString(wire.command(10, 1, out -> writeId(out, object, objectSize)));
            String preview = value.length() > 200 ? value.substring(0, 200) + "…" : value;
            return "\"" + escaped(preview).replace("\"", "\\\"") + "\"";
        }
        DataInputStream details = wire.command(9, 1, out -> writeId(out, object, objectSize));
        details.readUnsignedByte();
        long type = readId(details, typeSize);
        String signature = readString(wire.command(2, 1, out -> writeId(out, type, typeSize)));
        return typeName(signature) + "@" + Long.toHexString(object);
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final class Variable {
        final int slot;
        final String name;
        final char tag;
        Variable(int slot, String name, char tag) {
            this.slot = slot;
            this.name = name;
            this.tag = tag;
        }
    }

    private List<MethodInfo> methods(long type) throws IOException {
        DataInputStream reply = wire.command(2, 5, out -> writeId(out, type, typeSize));
        List<MethodInfo> result = new ArrayList<>();
        int count = readCount(reply);
        for (int i = 0; i < count; i++) {
            long id = readId(reply, methodSize);
            String name = readString(reply);
            readString(reply); // JVM descriptor
            result.add(new MethodInfo(id, name, reply.readInt()));
        }
        return result;
    }

    private List<Line> lineTable(long type, long method) throws IOException {
        DataInputStream reply;
        try {
            reply = wire.command(6, 1, out -> {
                writeId(out, type, typeSize);
                writeId(out, method, methodSize);
            });
        } catch (JdwpWire.CommandError e) {
            if (e.code == 101 || e.code == 511) return Collections.emptyList(); // absent info / native
            throw e;
        }
        reply.readLong(); // start and end indices
        reply.readLong();
        int count = readCount(reply);
        List<Line> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new Line(reply.readLong(), reply.readInt()));
        return result;
    }

    private void handleEvent(byte[] packet) {
        try {
            // VM_START may arrive before the reply to IDSizes.
            sizesReady.await();
            if (closing) return;
            DataInputStream event = new DataInputStream(new ByteArrayInputStream(packet));
            int policy = event.readUnsignedByte();
            int count = readCount(event);
            long thread = 0;
            String reason = null;
            for (int i = 0; i < count; i++) {
                int kind = event.readUnsignedByte();
                int request = event.readInt();
                if (kind == CLASS_PREPARE) {
                    readId(event, objectSize); // preparing thread, possibly absent
                    int tag = event.readUnsignedByte();
                    long type = readId(event, typeSize);
                    readString(event); // signature
                    event.readInt(); // class status
                    classPrepared(request, tag, type);
                    continue;
                }
                if (kind == VM_DEATH) {
                    wire.terminate(new IOException(text("targetExited")));
                    return;
                }
                if (kind != BREAKPOINT && kind != SINGLE_STEP && kind != VM_START) {
                    throw new IOException(text("unknownEvent", kind));
                }
                thread = readId(event, objectSize);
                if (kind == VM_START) {
                    reason = text("targetWaiting");
                } else {
                    event.readUnsignedByte(); // location type tag
                    readId(event, typeSize);
                    readId(event, methodSize);
                    event.readLong();
                    reason = kind == BREAKPOINT ? text("breakpointHit") : text("stepComplete");
                }
            }
            if (!closing && policy != 0) {
                if (reason != null) listener.onStopped(thread, reason);
                else wire.command(1, 9, JdwpWire.EMPTY); // release this class-prepare suspension
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            wire.terminate(e);
        }
    }

    @Override public synchronized void close() {
        if (closing) return;
        closing = true;
        try {
            wire.command(1, 6, JdwpWire.EMPTY); // Dispose releases debugger suspensions and requests.
        } catch (IOException ignored) {
            // The target may already have exited.
        } finally {
            wire.terminate(new IOException(text("debuggerDetached")));
        }
    }

    private static final class MethodInfo {
        final long id;
        final String name;
        final int modifiers;
        MethodInfo(long id, String name, int modifiers) {
            this.id = id;
            this.name = name;
            this.modifiers = modifiers;
        }
    }

    private static final class Line {
        final long index;
        final int number;
        Line(long index, int number) {
            this.index = index;
            this.number = number;
        }
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > in.available()) throw new IOException(text("entryCountInvalid"));
        return count;
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > in.available()) throw new IOException(text("jdwpStringInvalid"));
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String typeName(String signature) {
        if (signature.startsWith("L") && signature.endsWith(";")) {
            return signature.substring(1, signature.length() - 1).replace('/', '.');
        }
        return signature;
    }

    private static long readId(DataInputStream in, int size) throws IOException {
        long value = 0;
        for (int i = 0; i < size; i++) value = (value << 8) | in.readUnsignedByte();
        return value;
    }

    private static void writeId(DataOutputStream out, long value, int size) throws IOException {
        for (int byteIndex = size - 1; byteIndex >= 0; byteIndex--) {
            out.writeByte((int) (value >>> (byteIndex * 8)));
        }
    }
}
