// Android-side IPv4 network stack. Its only guest transport is inherited pipes.
package main

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"runtime"
	"syscall"
	"time"

	"github.com/containers/gvisor-tap-vsock/pkg/types"
	"github.com/containers/gvisor-tap-vsock/pkg/virtualnetwork"
)

const maxFrame = 65535

type pipeAddress struct{}

func (pipeAddress) Network() string { return "pipe" }
func (pipeAddress) String() string  { return "owned-avf-vsock" }

type packetPipe struct {
	input   io.Reader
	output  io.Writer
	pending []byte
}

// No loopback/unspecified/link-local access to Android. No host port forwards
// or localhost NAT mappings are installed in production.
func allowed(frame []byte) bool {
	if len(frame) < 14 {
		return false
	}
	kind := binary.BigEndian.Uint16(frame[12:14])
	if kind == 0x0806 {
		return len(frame) >= 42
	} // ARP
	if kind != 0x0800 || len(frame) < 34 || frame[14]>>4 != 4 {
		return false
	}
	destination := frame[30:34]
	if destination[0] == 0 || destination[0] == 127 ||
		(destination[0] == 169 && destination[1] == 254) {
		return false
	}
	if destination[0] >= 224 {
		header := int(frame[14]&15) * 4
		return destination[0] == 255 && destination[1] == 255 && destination[2] == 255 && destination[3] == 255 &&
			frame[23] == 17 && header >= 20 && len(frame) >= 14+header+8 &&
			binary.BigEndian.Uint16(frame[14+header:16+header]) == 68 &&
			binary.BigEndian.Uint16(frame[16+header:18+header]) == 67
	}
	return true
}

func (p *packetPipe) Read(b []byte) (int, error) {
	if len(b) == 0 {
		return 0, nil
	}
	for len(p.pending) == 0 {
		var prefix [4]byte
		if _, err := io.ReadFull(p.input, prefix[:]); err != nil {
			return 0, err
		}
		size := binary.BigEndian.Uint32(prefix[:])
		if size < 14 || size > maxFrame {
			return 0, errors.New("invalid Ethernet frame size")
		}
		packet := make([]byte, 4+int(size))
		copy(packet, prefix[:])
		if _, err := io.ReadFull(p.input, packet[4:]); err != nil {
			return 0, err
		}
		if allowed(packet[4:]) {
			p.pending = packet
		}
	}
	count := copy(b, p.pending)
	p.pending = p.pending[count:]
	return count, nil
}
func (p *packetPipe) Write(b []byte) (int, error) { return p.output.Write(b) }
func (p *packetPipe) Close() error {
	if c, ok := p.input.(io.Closer); ok {
		_ = c.Close()
	}
	if c, ok := p.output.(io.Closer); ok {
		_ = c.Close()
	}
	return nil
}
func (*packetPipe) LocalAddr() net.Addr              { return pipeAddress{} }
func (*packetPipe) RemoteAddr() net.Addr             { return pipeAddress{} }
func (*packetPipe) SetDeadline(time.Time) error      { return nil }
func (*packetPipe) SetReadDeadline(time.Time) error  { return nil }
func (*packetPipe) SetWriteDeadline(time.Time) error { return nil }

func main() {
	config := &types.Configuration{
		MTU: 1500, Subnet: "192.168.127.0/24", GatewayIP: "192.168.127.1",
		GatewayMacAddress: "5a:55:0a:00:00:01",
		DHCPStaticLeases:  map[string]string{"192.168.127.2": "5a:55:0a:00:00:02"},
	}
	if len(os.Args) != 1 {
		// Local fixture servers are reachable only in Linux CI, never Android.
		if runtime.GOOS != "linux" || len(os.Args) != 2 || os.Args[1] != "--ci" {
			os.Exit(2)
		}
		config.GatewayVirtualIPs = []string{"192.168.127.254"}
		config.NAT = map[string]string{"192.168.127.254": "127.0.0.1"}
		config.DNS = []types.Zone{{Name: "test.", Records: []types.Record{{Name: "fixture", IP: net.ParseIP("192.168.127.254")}}}}
	}
	network, err := virtualnetwork.New(config)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	connection := &packetPipe{input: os.Stdin, output: os.Stdout}
	go func() { <-ctx.Done(); _ = connection.Close() }()
	fmt.Fprintln(os.Stderr, "TERMUX_ARCH_NETWORK_HOST_READY")
	if err := network.AcceptQemu(ctx, connection); err != nil && ctx.Err() == nil && !errors.Is(err, io.EOF) {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
