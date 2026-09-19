package com.termux.api.shizuku;
interface IArchVmService {
    void destroy() = 16777114;
    String start(String publicKey) = 1;
    String status() = 2;
    String stop() = 3;
}
