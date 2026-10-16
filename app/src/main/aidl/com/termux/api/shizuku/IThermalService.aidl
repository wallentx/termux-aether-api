package com.termux.api.shizuku;

// Deliberately no arbitrary command, path, arguments, or settings methods.
interface IThermalService {
    void destroy() = 16777114;
    String readThermal() = 1;
}
