package com.fumizo07.fmzlinkr;

interface ILogCallback {
    void onLogEvent(String level, String tag, String message, String throwableStackTrace);
}
