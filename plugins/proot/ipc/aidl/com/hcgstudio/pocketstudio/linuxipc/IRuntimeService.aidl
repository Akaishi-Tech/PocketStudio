package com.hcgstudio.pocketstudio.linuxipc;

import com.hcgstudio.pocketstudio.linuxipc.IRunningCommand;
import android.os.ParcelFileDescriptor;

interface IRuntimeService {
    IRunningCommand start(String project, in String[] argv, boolean terminal, IBinder owner,
        in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout,
        in ParcelFileDescriptor stderr, in ParcelFileDescriptor exitCode);
}
