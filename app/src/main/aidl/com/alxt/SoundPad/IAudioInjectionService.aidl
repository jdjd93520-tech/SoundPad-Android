package com.alxt.SoundPad;

import android.os.ParcelFileDescriptor;

interface IAudioInjectionService {
    void destroy() = 16777114;
    String startInjection(in ParcelFileDescriptor audioPipe) = 1;
    void stopInjection() = 2;
    String getStatus() = 3;
}