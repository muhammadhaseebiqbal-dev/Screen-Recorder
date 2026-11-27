package com.haseeb.recorder.shizuku;

import android.os.ParcelFileDescriptor;

interface IEnhancedAudioService {
    int startPrivilegedCapture(in ParcelFileDescriptor writePipe, int excludeUid);
    void stopPrivilegedCapture();
    int getSampleRate();
    String getLastError();
    void destroy();
}
