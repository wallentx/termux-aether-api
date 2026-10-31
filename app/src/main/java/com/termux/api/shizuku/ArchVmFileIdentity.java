package com.termux.api.shizuku;

/** Pure comparison shared by the Android owner-lock path and host unit tests. */
final class ArchVmFileIdentity {
    private ArchVmFileIdentity() {}

    static boolean same(long pathDevice, long pathInode, long openedDevice, long openedInode) {
        return pathDevice == openedDevice && pathInode == openedInode;
    }
}
