// Guest-only relay: the host CID can reach only localhost SSH. No commands/paths.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>
#include <sys/wait.h>
#include <unistd.h>

struct channel { unsigned char data[16384]; size_t start, end; int eof; };

static int relay(int a, int b) {
    int fd[2] = {a, b};
    struct channel queue[2] = {0};
    for (int i = 0; i < 2; i++) if (fcntl(fd[i], F_SETFL, O_NONBLOCK)) return 1;
    for (;;) {
        struct pollfd events[2];
        for (int i = 0; i < 2; i++) {
            events[i] = (struct pollfd){.fd = fd[i], .events = 0};
            if (!queue[i].eof && queue[i].end == 0) events[i].events |= POLLIN;
            if (queue[1-i].end > queue[1-i].start) events[i].events |= POLLOUT;
            if (!events[i].events) events[i].fd = -1;
        }
        if (queue[0].eof && queue[1].eof && !queue[0].end && !queue[1].end) return 0;
        if (poll(events, 2, -1) < 0) { if (errno == EINTR) continue; return 1; }
        for (int i = 0; i < 2; i++) {
            if (events[i].revents & (POLLERR | POLLNVAL)) return 1;
            if ((events[i].revents & (POLLIN | POLLHUP)) && !queue[i].eof && !queue[i].end) {
                ssize_t count = read(fd[i], queue[i].data, sizeof(queue[i].data));
                if (count > 0) queue[i].end = (size_t)count;
                else if (!count) { queue[i].eof = 1; shutdown(fd[1-i], SHUT_WR); }
                else if (errno != EAGAIN && errno != EINTR) return 1;
            }
            struct channel *out = &queue[1-i];
            if ((events[i].revents & POLLOUT) && out->end > out->start) {
                ssize_t count = write(fd[i], out->data + out->start, out->end - out->start);
                if (count > 0) {
                    out->start += (size_t)count;
                    if (out->start == out->end) out->start = out->end = 0;
                } else if (count < 0 && errno != EAGAIN && errno != EINTR) return 1;
            }
        }
    }
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    int listener = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_vm address = {.svm_family = AF_VSOCK, .svm_cid = VMADDR_CID_ANY, .svm_port = 2222};
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, sizeof(address)) || listen(listener, 8)) {
        perror("guest vsock listener"); return 1;
    }
    int children = 0;
    for (;;) {
        while (waitpid(-1, NULL, WNOHANG) > 0) children--;
        struct pollfd pending = {.fd = listener, .events = POLLIN};
        int available = poll(&pending, 1, 1000);
        if (available <= 0) continue;
        struct sockaddr_vm peer;
        socklen_t size = sizeof(peer);
        int incoming = accept4(listener, (struct sockaddr *)&peer, &size, SOCK_CLOEXEC);
        if (incoming < 0) continue;
        if (peer.svm_cid != VMADDR_CID_HOST || children >= 8) { close(incoming); continue; }
        pid_t child = fork();
        if (!child) {
            close(listener);
            int target = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
            struct sockaddr_in ssh = {.sin_family = AF_INET, .sin_port = htons(22),
                                     .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
            if (target < 0 || connect(target, (struct sockaddr *)&ssh, sizeof(ssh))) _exit(2);
            _exit(relay(incoming, target));
        }
        if (child > 0) children++;
        close(incoming);
    }
}
