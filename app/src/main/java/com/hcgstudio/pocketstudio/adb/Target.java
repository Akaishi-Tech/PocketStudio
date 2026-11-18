package com.hcgstudio.pocketstudio.adb;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** A developer-authorized device connection and its multiplexed services. */
public interface Target extends Closeable {
    interface Stream extends Closeable {
        InputStream input();
        OutputStream output();
    }
    String shell(String command) throws IOException;
    void install(InputStream apk, long size) throws IOException;
    Stream open(String service) throws IOException;
    boolean isConnected();
    @Override void close();
}

