// Guest TAP <-> owned AVF vsock. QEMU framing: big-endian uint32 + Ethernet frame.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <linux/vm_sockets.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

#define MAX_FRAME 65535

static int tap_open(void) {
    int fd = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
    struct ifreq request = {.ifr_flags = IFF_TAP | IFF_NO_PI};
    strcpy(request.ifr_name, "avf0");
    if (fd < 0 || ioctl(fd, TUNSETIFF, &request)) { perror("TAP create"); exit(1); }
    int ctl = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    request.ifr_hwaddr.sa_family = ARPHRD_ETHER;
    unsigned char mac[] = {0x5a, 0x55, 0x0a, 0, 0, 2};
    memcpy(request.ifr_hwaddr.sa_data, mac, sizeof(mac));
    if (ctl < 0 || ioctl(ctl, SIOCSIFHWADDR, &request)) { perror("TAP MAC"); exit(1); }
    request.ifr_mtu = 1500;
    if (ioctl(ctl, SIOCSIFMTU, &request)) { perror("TAP MTU"); exit(1); }
    request.ifr_flags = IFF_UP;
    if (ioctl(ctl, SIOCSIFFLAGS, &request)) { perror("TAP up"); exit(1); }
    close(ctl);
    return fd;
}

static int pump(int tap, int input, int output) {
    unsigned char tx[MAX_FRAME + 4], rx[MAX_FRAME + 4];
    size_t sent = 0, queued = 0, received = 0, wanted = 4;
    if (fcntl(tap, F_SETFL, O_NONBLOCK) || fcntl(input, F_SETFL, O_NONBLOCK) ||
        fcntl(output, F_SETFL, O_NONBLOCK)) return 1;
    for (;;) {
        struct pollfd p[3] = {
            {.fd = tap, .events = (queued == 0 ? POLLIN : 0) | (received == wanted && wanted > 4 ? POLLOUT : 0)},
            {.fd = received < wanted ? input : -1, .events = POLLIN},
            {.fd = queued > sent ? output : -1, .events = POLLOUT}
        };
        if (poll(p, 3, -1) < 0) { if (errno == EINTR) continue; return 1; }
        for (int i = 0; i < 3; i++) if (p[i].revents & (POLLERR | POLLNVAL)) return 1;
        if ((p[1].revents & (POLLIN | POLLHUP)) && received < wanted) {
            ssize_t n = read(input, rx + received, wanted - received);
            if (!n) return 0;
            if (n < 0) { if (errno != EINTR && errno != EAGAIN) return 1; }
            else {
                received += (size_t)n;
                if (received == 4 && wanted == 4) {
                    uint32_t length;
                    memcpy(&length, rx, 4); length = ntohl(length);
                    if (length < 14 || length > MAX_FRAME) return 1;
                    wanted = 4 + length;
                }
            }
        }
        if ((p[0].revents & POLLOUT) && received == wanted && wanted > 4) {
            ssize_t n = write(tap, rx + 4, wanted - 4);
            if (n == (ssize_t)(wanted - 4)) { received = 0; wanted = 4; }
            else if (n >= 0 || (errno != EINTR && errno != EAGAIN)) return 1;
        }
        if ((p[0].revents & POLLIN) && !queued) {
            ssize_t n = read(tap, tx + 4, MAX_FRAME);
            if (n >= 14) {
                uint32_t length = htonl((uint32_t)n); memcpy(tx, &length, 4);
                sent = 0; queued = 4 + (size_t)n;
            } else if (n < 0 && errno != EINTR && errno != EAGAIN) return 1;
        }
        if ((p[2].revents & POLLOUT) && queued > sent) {
            ssize_t n = write(output, tx + sent, queued - sent);
            if (n > 0) { sent += (size_t)n; if (sent == queued) sent = queued = 0; }
            else if (n < 0 && errno != EINTR && errno != EAGAIN) return 1;
        }
        if (p[2].revents & POLLHUP) return 0;
    }
}

int main(int argc, char **argv) {
    signal(SIGPIPE, SIG_IGN);
    if (argc != 1 && (argc != 2 || strcmp(argv[1], "--stdio"))) return 2;
    int tap = tap_open();
    if (argc == 2) return pump(tap, STDIN_FILENO, STDOUT_FILENO);
    int server = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_vm address = {.svm_family = AF_VSOCK, .svm_cid = VMADDR_CID_ANY, .svm_port = 2223};
    if (server < 0 || bind(server, (struct sockaddr *)&address, sizeof(address)) || listen(server, 1)) {
        perror("network vsock listener"); return 1;
    }
    for (;;) {
        struct sockaddr_vm peer;
        socklen_t size = sizeof(peer);
        int fd = accept4(server, (struct sockaddr *)&peer, &size, SOCK_CLOEXEC);
        if (fd < 0) { if (errno == EINTR) continue; return 1; }
        if (peer.svm_cid == VMADDR_CID_HOST) (void)pump(tap, fd, fd);
        close(fd);
    }
}
