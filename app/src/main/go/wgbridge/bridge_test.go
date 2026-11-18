package main

import (
	"bufio"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keypair(t *testing.T, seed byte) (priv, pub []byte) {
	priv = make([]byte, 32)
	for i := range priv {
		priv[i] = seed + byte(i)
	}
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return
}

// Sağlayıcıyı taklit eden yerel WireGuard sunucusu: 10.64.0.1 üzerinde TCP ve UDP yankı.
func startServer(t *testing.T, clientPub []byte, serverPriv []byte) (port int, stop func()) {
	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.64.0.1")}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "srv: "))
	l, _ := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	port = l.LocalAddr().(*net.UDPAddr).Port
	l.Close()
	uapi := fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.64.0.2/32\n",
		hex.EncodeToString(serverPriv), port, hex.EncodeToString(clientPub))
	if err := dev.IpcSet(uapi); err != nil {
		t.Fatal(err)
	}
	dev.Up()
	ln, err := tnet.ListenTCP(&net.TCPAddr{Port: 7000})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); io.Copy(c, c) }()
		}
	}()
	uc, err := tnet.ListenUDP(&net.UDPAddr{Port: 7001})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := uc.ReadFrom(buf)
			if err != nil {
				return
			}
			uc.WriteTo(append([]byte("echo:"), buf[:n]...), from)
		}
	}()
	return port, func() { ln.Close(); uc.Close(); dev.Close() }
}

func startBridge(t *testing.T) (*Bridge, func()) {
	cPriv, cPub := keypair(t, 1)
	sPriv, sPub := keypair(t, 90)
	port, stopServer := startServer(t, cPub, sPriv)
	uapi := fmt.Sprintf("private_key=%s\nreplace_peers=true\npublic_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n",
		hex.EncodeToString(cPriv), hex.EncodeToString(sPub), port)
	cfg, err := ParseConfig(uapi, "10.64.0.2/32", "10.64.0.1", 1420, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	b, err := Start(cfg)
	if err != nil {
		t.Fatal(err)
	}
	_ = base64.StdEncoding
	return b, func() { b.Close(); stopServer() }
}

func socksHandshake(t *testing.T, c net.Conn, withAuth bool) {
	if withAuth {
		c.Write([]byte{5, 1, 2})
	} else {
		c.Write([]byte{5, 1, 0})
	}
	resp := make([]byte, 2)
	if _, err := io.ReadFull(c, resp); err != nil {
		t.Fatal(err)
	}
	if withAuth {
		if resp[1] != 2 {
			t.Fatalf("method %d", resp[1])
		}
		u := "10.10.10.10|5555"
		c.Write(append(append([]byte{1, byte(len(u))}, u...), 1, 'x'))
		io.ReadFull(c, resp)
		if resp[1] != 0 {
			t.Fatal("auth rejected")
		}
	}
}

func TestConnectThroughWireGuard(t *testing.T) {
	b, stop := startBridge(t)
	defer stop()
	for _, auth := range []bool{false, true} {
		c, err := net.Dial("tcp", b.Addr())
		if err != nil {
			t.Fatal(err)
		}
		c.SetDeadline(time.Now().Add(15 * time.Second))
		socksHandshake(t, c, auth)
		c.Write([]byte{5, 1, 0, 1, 10, 64, 0, 1, 0x1b, 0x58}) // 10.64.0.1:7000
		rep := make([]byte, 10)
		if _, err := io.ReadFull(c, rep); err != nil {
			t.Fatal(err)
		}
		if rep[1] != 0 {
			t.Fatalf("connect rep %d", rep[1])
		}
		c.Write([]byte("merhaba"))
		got := make([]byte, 7)
		if _, err := io.ReadFull(c, got); err != nil || string(got) != "merhaba" {
			t.Fatalf("echo %q %v", got, err)
		}
		c.Close()
	}
	if !strings.Contains(b.Status(), "handshake=") || strings.Contains(b.Status(), "handshake=0;") {
		t.Fatalf("no handshake: %s", b.Status())
	}
}

func TestUdpThroughWireGuard(t *testing.T) {
	b, stop := startBridge(t)
	defer stop()
	c, err := net.Dial("tcp", b.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	socksHandshake(t, c, false)
	c.Write([]byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	r := bufio.NewReader(c)
	rep := make([]byte, 4)
	io.ReadFull(r, rep)
	if rep[1] != 0 {
		t.Fatalf("udp rep %d", rep[1])
	}
	ip := make([]byte, 4)
	io.ReadFull(r, ip)
	pb := make([]byte, 2)
	io.ReadFull(r, pb)
	relay := &net.UDPAddr{IP: net.IP(ip), Port: int(binary.BigEndian.Uint16(pb))}
	u, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer u.Close()
	pkt := append([]byte{0, 0, 0, 1, 10, 64, 0, 1, 0x1b, 0x59}, "ping"...)
	buf := make([]byte, 2048)
	for i := 0; i < 5; i++ {
		u.WriteToUDP(pkt, relay)
		u.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, _, err := u.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		dst, payload, ok := parseUDPHeader(buf[:n])
		if !ok || string(payload) != "echo:ping" || dst.host != "10.64.0.1" || dst.port != 7001 {
			t.Fatalf("bad reply %v %q", dst, payload)
		}
		return
	}
	t.Fatal("no udp reply")
}

func TestConnectFailureReplies(t *testing.T) {
	b, stop := startBridge(t)
	defer stop()
	c, _ := net.Dial("tcp", b.Addr())
	defer c.Close()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	socksHandshake(t, c, false)
	c.Write([]byte{5, 1, 0, 1, 10, 64, 0, 1, 0, 9}) // kapalı port
	rep := make([]byte, 10)
	if _, err := io.ReadFull(c, rep); err != nil {
		t.Fatal(err)
	}
	if rep[1] == 0 {
		t.Fatal("expected failure reply")
	}
}

func TestParseConfig(t *testing.T) {
	cfg, err := ParseConfig("x", "10.2.0.2/32, fd00::2/128", "10.2.0.1, example.org", 0, "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Addresses) != 2 || len(cfg.DNS) != 1 || cfg.MTU != 1280 {
		t.Fatalf("%+v", cfg)
	}
	if _, err := ParseConfig("x", "", "", 0, ""); err == nil {
		t.Fatal("expected error")
	}
}

// ExitRouter/ExitConnector hedefi daima alan adı türüyle (ATYP 3) ister; IP metni de olabilir.
func TestConnectWithDomainAtyp(t *testing.T) {
	b, stop := startBridge(t)
	defer stop()
	c, err := net.Dial("tcp", b.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(15 * time.Second))
	socksHandshake(t, c, false)
	host := "10.64.0.1"
	req := append([]byte{5, 1, 0, 3, byte(len(host))}, host...)
	c.Write(append(req, 0x1b, 0x58))
	rep := make([]byte, 10)
	if _, err := io.ReadFull(c, rep); err != nil || rep[1] != 0 {
		t.Fatalf("rep %v %v", rep, err)
	}
	c.Write([]byte("abc"))
	got := make([]byte, 3)
	if _, err := io.ReadFull(c, got); err != nil || string(got) != "abc" {
		t.Fatalf("echo %q %v", got, err)
	}
}
