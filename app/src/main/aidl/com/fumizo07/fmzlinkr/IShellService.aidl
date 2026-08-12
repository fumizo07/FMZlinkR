package com.fumizo07.fmzlinkr;

import android.os.ParcelFileDescriptor;
import com.fumizo07.fmzlinkr.ILogCallback;

interface IShellService {
    void setLogCallback(ILogCallback appLoggerCallback, boolean isRedactionEnabled) = 1;

    /** Must be called before the VoIP playback track is created. */
    boolean armVoipCapture() = 7;
    void disarmVoipCapture() = 8;

    /** FMZlinkR currently records Opus at the supplied bitrate. */
    boolean startVoipRecording(int audioBitRate, in ParcelFileDescriptor outFd) = 9;
    void stopVoipRecording() = 10;
    boolean voipFarPartyHeard() = 11;
    int voipCallAppUid() = 12;
    boolean voipNearPartyHeard() = 13;

    /** Shizuku special destroy transaction. Terminates only FMZlinkR's UserService process. */
    void destroy() = 16777114;
}
