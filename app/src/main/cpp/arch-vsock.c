#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>
#include <unistd.h>

// Only the owned guest's fixed SSH service is reachable through this bridge.
JNIEXPORT jint JNICALL
Java_com_termux_api_shizuku_ArchVmBridge_connectVsock(JNIEnv *env, jclass type, jint cid) {
    (void)type;
    int fd = -1, error = EINVAL;
    if (getuid() != 2000 || cid < 3) goto failed;
    fd = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) { error = errno; goto failed; }
    struct sockaddr_vm address = {.svm_family = AF_VSOCK, .svm_cid = (unsigned int)cid,
                                 .svm_port = 2222};
    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        if (errno != EINPROGRESS) { error = errno; goto failed; }
        struct pollfd wait = {.fd = fd, .events = POLLOUT};
        int result;
        do { result = poll(&wait, 1, 3000); } while (result < 0 && errno == EINTR);
        if (result <= 0) { error = result == 0 ? ETIMEDOUT : errno; goto failed; }
        socklen_t size = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) < 0) { error = errno; goto failed; }
        if (error) goto failed;
    }
    if (fcntl(fd, F_SETFL, 0) < 0) { error = errno; goto failed; }
    return fd;
failed:
    if (fd >= 0) close(fd);
    jclass exception = (*env)->FindClass(env, "java/io/IOException");
    if (exception) {
        char message[80];
        snprintf(message, sizeof(message), "AVF vsock connection failed (errno %d)", error);
        (*env)->ThrowNew(env, exception, message);
    }
    return -1;
}
