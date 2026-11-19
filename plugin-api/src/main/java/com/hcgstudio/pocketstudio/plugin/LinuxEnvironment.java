package com.hcgstudio.pocketstudio.plugin;

import android.content.Intent;
import java.io.IOException;

/** A signed environment provider. Construct with (host Context, plugin Context). */
public interface LinuxEnvironment {
    String API = "com.hcgstudio.pocketstudio.LINUX_API";
    String ENTRY = "com.hcgstudio.pocketstudio.LINUX_ENTRY";
    int VERSION = 1;

    /** Explicit activity for preparing the environment, or null when no setup is needed. */
    Intent setupIntent();

    /** Start argv in /home/studio/projects/project, creating the directory on first use.
     * Invoked on an I/O thread. Without a terminal, all three streams must be binary clean.
     * Closing stdin sends EOF. Destroying the process cancels the command. */
    Process start(String project, String[] argv, boolean terminal) throws IOException;
}
