package com.hcgstudio.pocketstudio.prootplugin;

import com.hcgstudio.pocketstudio.linuxipc.RuntimeService;
import java.io.IOException;

public final class ProotService extends RuntimeService {
    @Override protected Process launch(String project, String[] argv, boolean terminal) throws IOException {
        return new DebianRunner(this).start(project, argv, terminal);
    }
}
