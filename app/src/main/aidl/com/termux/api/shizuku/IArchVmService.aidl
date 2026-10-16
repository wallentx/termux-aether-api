package com.termux.api.shizuku;
interface IArchVmService {
    void destroy() = 16777114;
    String start(String publicKey) = 1;
    String status() = 2;
    String stop() = 3;
    String startWithMemory(String publicKey, int memoryMiB) = 4;
    String resizeMemory(int retainedMiB) = 6;
    String growDisk(long bytes) = 7;
    String session(String operation, String token, boolean keepMemory) = 5;
}
