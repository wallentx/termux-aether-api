package main

import (
	"bytes"
	"encoding/binary"
	"io"
	"testing"
)

func ipv4(destination [4]byte) []byte {
	frame := make([]byte, 42)
	frame[12], frame[13], frame[14], frame[23] = 8, 0, 0x45, 17
	copy(frame[30:34], destination[:])
	return frame
}
func packet(frame []byte) []byte {
	p := make([]byte, 4+len(frame))
	binary.BigEndian.PutUint32(p, uint32(len(frame)))
	copy(p[4:], frame)
	return p
}
func TestRejectHostOnlyDestinations(t *testing.T) {
	for _, ip := range [][4]byte{{127, 0, 0, 1}, {127, 1, 2, 3}, {0, 0, 0, 0}, {169, 254, 169, 254}, {224, 0, 0, 251}} {
		if allowed(ipv4(ip)) {
			t.Fatalf("allowed %v", ip)
		}
	}
	for _, ip := range [][4]byte{{1, 1, 1, 1}, {192, 168, 1, 1}, {192, 168, 127, 1}} {
		if !allowed(ipv4(ip)) {
			t.Fatalf("blocked %v", ip)
		}
	}
}
func TestDHCPBroadcastOnly(t *testing.T) {
	frame := ipv4([4]byte{255, 255, 255, 255})
	if allowed(frame) {
		t.Fatal("allowed arbitrary broadcast")
	}
	binary.BigEndian.PutUint16(frame[34:36], 68)
	binary.BigEndian.PutUint16(frame[36:38], 67)
	if !allowed(frame) {
		t.Fatal("blocked DHCP")
	}
}
func TestFramingSkipsBlockedPacketAndPreservesShortReads(t *testing.T) {
	good := packet(ipv4([4]byte{1, 1, 1, 1}))
	input := append(packet(ipv4([4]byte{127, 0, 0, 1})), good...)
	p := &packetPipe{input: bytes.NewReader(input)}
	var result []byte
	for {
		var b [3]byte
		n, err := p.Read(b[:])
		result = append(result, b[:n]...)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	if !bytes.Equal(result, good) {
		t.Fatal("framing changed")
	}
}
func TestOversizedFrameFailsBeforeAllocation(t *testing.T) {
	p := &packetPipe{input: bytes.NewReader([]byte{255, 255, 255, 255})}
	if _, err := p.Read(make([]byte, 4)); err == nil {
		t.Fatal("accepted oversized frame")
	}
}
