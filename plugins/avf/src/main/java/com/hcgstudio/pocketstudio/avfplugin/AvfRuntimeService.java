package com.hcgstudio.pocketstudio.avfplugin;

import com.hcgstudio.pocketstudio.linuxipc.RuntimeService;

import java.io.IOException;

public final class AvfRuntimeService extends RuntimeService {
    @Override protected Process launch(String project, String[] argv, boolean terminal) throws IOException {
        return AvfSsh.start(this, project, argv, terminal);
    }
}
