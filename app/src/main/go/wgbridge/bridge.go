// Package main: WireGuard -> yerel SOCKS5 köprüsü.
//
// Android aynı anda yalnızca BİR VpnService çalıştırır: Windscribe/Proton
// uygulaması açıkken DPI Bypass açılınca onlar kapanır. Bu köprü, sağlayıcının
// verdiği WireGuard yapılandırmasını DPI Bypass'ın KENDİ sürecinde,
// kullanıcı alanında (gVisor netstack) çalıştırır ve 127.0.0.1 üzerinde bir
// SOCKS5 sunucusu olarak sunar. Böylece tek TUN içinde üç yol olur:
//
//	VPN uygulamaları -> ExitRouter -> bu köprü -> WireGuard -> sağlayıcı
//	DPI uygulamaları -> ExitRouter -> ByeDPI  -> İnternet
//	diğerleri        -> TUN dışında (Android kapsamı) -> doğrudan
//
// Köprünün kendi UDP soketleri DPI Bypass sürecine aittir; uygulama VPN
// kapsamından hariç tutulduğu için WireGuard paketleri tünele geri girmez.
package main

import (
	"bufio"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// Config köprünün çalışması için gerekenler; Kotlin tarafı wg-quick
// dosyasını ayrıştırıp UAPI biçimine çevirir (anahtarlar hex).
type Config struct {
	UAPI       string
	Addresses  []netip.Addr
	DNS        []netip.Addr
	MTU        int
	ListenAddr string // ör. 127.0.0.1:1082
}

type Bridge struct {
	dev  *device.Device
	tnet *netstack.Net
	ln   net.Listener
	v4   netip.Addr
	v6   netip.Addr

	mu      sync.Mutex
	conns   map[io.Closer]struct{}
	closed  bool
	started time.Time
}

const (
	handshakeTimeout = 10 * time.Second
	dialTimeout      = 12 * time.Second
	udpIdleTimeout   = 3 * time.Minute
)

// ParseConfig "anahtar=değer" satırlarından Config üretir (JNI tarafı bunu kullanır).
func ParseConfig(uapi, addresses, dns string, mtu int, listen string) (Config, error) {
	cfg := Config{UAPI: uapi, MTU: mtu, ListenAddr: listen}
	for _, a := range splitList(addresses) {
		p, err := netip.ParsePrefix(a)
		if err != nil {
			ip, err2 := netip.ParseAddr(a)
			if err2 != nil {
				return cfg, fmt.Errorf("bad address %q", a)
			}
			cfg.Addresses = append(cfg.Addresses, ip)
			continue
		}
		cfg.Addresses = append(cfg.Addresses, p.Addr())
	}
	for _, d := range splitList(dns) {
		ip, err := netip.ParseAddr(d)
		if err != nil {
			continue // DNS satırında arama alanı olabilir; yok sayılır
		}
		cfg.DNS = append(cfg.DNS, ip)
	}
	if len(cfg.Addresses) == 0 {
		return cfg, errors.New("no interface address")
	}
	if len(cfg.DNS) == 0 {
		cfg.DNS = []netip.Addr{netip.MustParseAddr("1.1.1.1")}
	}
	if cfg.MTU <= 0 {
		cfg.MTU = 1280
	}
	return cfg, nil
}

func splitList(s string) []string {
	var out []string
	for _, f := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' }) {
		if f = strings.TrimSpace(f); f != "" {
			out = append(out, f)
		}
	}
	return out
}

// Start WireGuard aygıtını kurar ve SOCKS5 dinleyicisini açar.
func Start(cfg Config) (*Bridge, error) {
	tunDev, tnet, err := netstack.CreateNetTUN(cfg.Addresses, cfg.DNS, cfg.MTU)
	if err != nil {
		return nil, fmt.Errorf("netstack: %w", err)
	}
	logger := device.NewLogger(device.LogLevelError, "wg: ")
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)
	if err := dev.IpcSet(cfg.UAPI); err != nil {
		dev.Close()
		return nil, fmt.Errorf("config: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("up: %w", err)
	}
	ln, err := net.Listen("tcp", cfg.ListenAddr)
	if err != nil {
		dev.Close()
		return nil, fmt.Errorf("listen: %w", err)
	}
	b := &Bridge{dev: dev, tnet: tnet, ln: ln, conns: map[io.Closer]struct{}{}, started: time.Now()}
	for _, a := range cfg.Addresses {
		if a.Is4() && !b.v4.IsValid() {
			b.v4 = a
		} else if a.Is6() && !a.Is4In6() && !b.v6.IsValid() {
			b.v6 = a
		}
	}
	go b.acceptLoop()
	return b, nil
}

// Addr dinlenen yerel adres.
func (b *Bridge) Addr() string { return b.ln.Addr().String() }

func (b *Bridge) track(c io.Closer) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		c.Close()
		return false
	}
	b.conns[c] = struct{}{}
	return true
}

func (b *Bridge) untrack(c io.Closer) {
	b.mu.Lock()
	delete(b.conns, c)
	b.mu.Unlock()
}

// Close dinleyiciyi, açık bağlantıları ve WireGuard aygıtını kapatır.
func (b *Bridge) Close() {
	b.mu.Lock()
	if b.closed {
		b.mu.Unlock()
		return
	}
	b.closed = true
	conns := b.conns
	b.conns = map[io.Closer]struct{}{}
	b.mu.Unlock()
	b.ln.Close()
	for c := range conns {
		c.Close()
	}
	b.dev.Close()
}

// Status "handshake=<unix sn>;rx=<bayt>;tx=<bayt>" döndürür.
func (b *Bridge) Status() string {
	s, err := b.dev.IpcGet()
	if err != nil {
		return "error=" + err.Error()
	}
	var hs, rx, tx int64
	sc := bufio.NewScanner(strings.NewReader(s))
	for sc.Scan() {
		k, v, ok := strings.Cut(sc.Text(), "=")
		if !ok {
			continue
		}
		n, _ := strconv.ParseInt(v, 10, 64)
		switch k {
		case "last_handshake_time_sec":
			if n > hs {
				hs = n
			}
		case "rx_bytes":
			rx += n
		case "tx_bytes":
			tx += n
		}
	}
	return fmt.Sprintf("handshake=%d;rx=%d;tx=%d", hs, rx, tx)
}

func (b *Bridge) acceptLoop() {
	for {
		c, err := b.ln.Accept()
		if err != nil {
			return
		}
		if !b.track(c) {
			return
		}
		go func() {
			defer b.untrack(c)
			defer c.Close()
			b.handle(c)
		}()
	}
}

// ---- SOCKS5 ----

const (
	socksVer       = 5
	cmdConnect     = 1
	cmdUDP         = 3
	atypV4         = 1
	atypDomain     = 3
	atypV6         = 4
	repOK          = 0
	repFail        = 1
	repNetUnreach  = 3
	repHostUnreach = 4
	repRefused     = 5
	repCmdUnsupp   = 7
)

type target struct {
	host string // alan adı ya da IP metni
	port uint16
}

func (t target) String() string { return net.JoinHostPort(t.host, strconv.Itoa(int(t.port))) }

func (b *Bridge) handle(c net.Conn) {
	c.SetDeadline(time.Now().Add(handshakeTimeout))
	r := bufio.NewReader(c)
	head := make([]byte, 2)
	if _, err := io.ReadFull(r, head); err != nil || head[0] != socksVer {
		return
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	method := byte(0xFF)
	for _, m := range methods {
		if m == 0 {
			method = 0
			break
		}
		if m == 2 {
			method = 2
		}
	}
	if _, err := c.Write([]byte{socksVer, method}); err != nil || method == 0xFF {
		return
	}
	if method == 2 {
		// Kullanıcı adı/parola yalnızca yerel istemciden gelir; içerik denetlenmez.
		vh := make([]byte, 2)
		if _, err := io.ReadFull(r, vh); err != nil {
			return
		}
		if _, err := io.CopyN(io.Discard, r, int64(vh[1])); err != nil {
			return
		}
		pl, err := r.ReadByte()
		if err != nil {
			return
		}
		if _, err := io.CopyN(io.Discard, r, int64(pl)); err != nil {
			return
		}
		if _, err := c.Write([]byte{1, 0}); err != nil {
			return
		}
	}
	req := make([]byte, 3)
	if _, err := io.ReadFull(r, req); err != nil || req[0] != socksVer {
		return
	}
	t, err := readAddr(r)
	if err != nil {
		return
	}
	switch req[1] {
	case cmdConnect:
		b.connect(c, r, t)
	case cmdUDP:
		b.udpAssociate(c)
	default:
		writeReply(c, repCmdUnsupp, nil)
	}
}

func readAddr(r *bufio.Reader) (target, error) {
	atyp, err := r.ReadByte()
	if err != nil {
		return target{}, err
	}
	var host string
	switch atyp {
	case atypV4:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(r, ip); err != nil {
			return target{}, err
		}
		host = netip.AddrFrom4([4]byte(ip)).String()
	case atypV6:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(r, ip); err != nil {
			return target{}, err
		}
		host = netip.AddrFrom16([16]byte(ip)).Unmap().String()
	case atypDomain:
		n, err := r.ReadByte()
		if err != nil {
			return target{}, err
		}
		name := make([]byte, n)
		if _, err := io.ReadFull(r, name); err != nil {
			return target{}, err
		}
		host = string(name)
	default:
		return target{}, errors.New("bad atyp")
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(r, pb); err != nil {
		return target{}, err
	}
	return target{host, binary.BigEndian.Uint16(pb)}, nil
}

func writeReply(w io.Writer, rep byte, bound *net.UDPAddr) error {
	if bound == nil {
		_, err := w.Write([]byte{socksVer, rep, 0, atypV4, 0, 0, 0, 0, 0, 0})
		return err
	}
	ip := bound.IP.To4()
	buf := []byte{socksVer, rep, 0, atypV4}
	if ip == nil {
		buf[3] = atypV6
		ip = bound.IP.To16()
	}
	buf = append(buf, ip...)
	buf = binary.BigEndian.AppendUint16(buf, uint16(bound.Port))
	_, err := w.Write(buf)
	return err
}

func replyFor(err error) byte {
	msg := err.Error()
	switch {
	case strings.Contains(msg, "refused"):
		return repRefused
	case strings.Contains(msg, "no route"), strings.Contains(msg, "unreachable"):
		return repNetUnreach
	case strings.Contains(msg, "timeout"), strings.Contains(msg, "timed out"), strings.Contains(msg, "deadline"):
		return repHostUnreach
	case strings.Contains(msg, "no such host"), strings.Contains(msg, "lookup"), strings.Contains(msg, "dns"):
		return repHostUnreach
	}
	return repFail
}

func (b *Bridge) connect(c net.Conn, r *bufio.Reader, t target) {
	ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
	remote, err := b.tnet.DialContext(ctx, "tcp", t.String())
	cancel()
	if err != nil {
		writeReply(c, replyFor(err), nil)
		return
	}
	if !b.track(remote) {
		return
	}
	defer b.untrack(remote)
	defer remote.Close()
	if err := writeReply(c, repOK, nil); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	// İstemci yanıtı beklemeden veri gönderdiyse bufio'da kalmış olabilir.
	if n := r.Buffered(); n > 0 {
		pending, _ := r.Peek(n)
		if _, err := remote.Write(pending); err != nil {
			return
		}
		r.Discard(n)
	}
	done := make(chan struct{}, 1)
	go func() {
		io.Copy(c, remote)
		closeWrite(c)
		done <- struct{}{}
	}()
	io.Copy(remote, c)
	closeWrite(remote)
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
	} else {
		c.Close()
	}
}

// udpAssociate: istemciye yerel bir UDP aktarım adresi verir; datagramları
// WireGuard içindeki netstack UDP soketlerinden gönderir/alır. Denetim TCP
// bağlantısı kapanınca birliktelik biter.
func (b *Bridge) udpAssociate(c net.Conn) {
	local, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		writeReply(c, repFail, nil)
		return
	}
	if !b.track(local) {
		return
	}
	defer b.untrack(local)
	defer local.Close()
	if err := writeReply(c, repOK, local.LocalAddr().(*net.UDPAddr)); err != nil {
		return
	}
	c.SetDeadline(time.Time{})

	a := &udpAssoc{b: b, local: local, dns: map[string]netip.Addr{}}
	defer a.close()
	go func() {
		io.Copy(io.Discard, c) // denetim bağlantısı kapanınca döner
		local.Close()
	}()
	a.forward()
}

type udpAssoc struct {
	b      *Bridge
	local  *net.UDPConn
	mu     sync.Mutex
	client *net.UDPAddr
	up4    net.PacketConn
	up6    net.PacketConn
	dns    map[string]netip.Addr
}

func (a *udpAssoc) close() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.up4 != nil {
		a.up4.Close()
	}
	if a.up6 != nil {
		a.up6.Close()
	}
}

func (a *udpAssoc) upstream(dst netip.Addr) (net.PacketConn, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if dst.Is4() {
		if a.up4 == nil {
			if !a.b.v4.IsValid() {
				return nil, errors.New("no ipv4 address")
			}
			pc, err := a.b.tnet.ListenUDPAddrPort(netip.AddrPortFrom(a.b.v4, 0))
			if err != nil {
				return nil, err
			}
			a.up4 = pc
			go a.backward(pc)
		}
		return a.up4, nil
	}
	if a.up6 == nil {
		if !a.b.v6.IsValid() {
			return nil, errors.New("no ipv6 address")
		}
		pc, err := a.b.tnet.ListenUDPAddrPort(netip.AddrPortFrom(a.b.v6, 0))
		if err != nil {
			return nil, err
		}
		a.up6 = pc
		go a.backward(pc)
	}
	return a.up6, nil
}

func (a *udpAssoc) resolve(host string) (netip.Addr, error) {
	if ip, err := netip.ParseAddr(host); err == nil {
		return ip.Unmap(), nil
	}
	a.mu.Lock()
	ip, ok := a.dns[host]
	a.mu.Unlock()
	if ok {
		return ip, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	addrs, err := a.b.tnet.LookupContextHost(ctx, host)
	if err != nil {
		return netip.Addr{}, err
	}
	for _, s := range addrs {
		if ip, err := netip.ParseAddr(s); err == nil {
			ip = ip.Unmap()
			if (ip.Is4() && a.b.v4.IsValid()) || (ip.Is6() && a.b.v6.IsValid()) {
				a.mu.Lock()
				a.dns[host] = ip
				a.mu.Unlock()
				return ip, nil
			}
		}
	}
	return netip.Addr{}, errors.New("no usable address")
}

func (a *udpAssoc) forward() {
	buf := make([]byte, 65535)
	for {
		a.local.SetReadDeadline(time.Now().Add(udpIdleTimeout))
		n, from, err := a.local.ReadFromUDP(buf)
		if err != nil {
			return
		}
		a.mu.Lock()
		a.client = from
		a.mu.Unlock()
		t, payload, ok := parseUDPHeader(buf[:n])
		if !ok {
			continue
		}
		ip, err := a.resolve(t.host)
		if err != nil {
			continue
		}
		up, err := a.upstream(ip)
		if err != nil {
			continue
		}
		up.WriteTo(payload, net.UDPAddrFromAddrPort(netip.AddrPortFrom(ip, t.port)))
	}
}

func (a *udpAssoc) backward(pc net.PacketConn) {
	buf := make([]byte, 65535)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		ua, ok := from.(*net.UDPAddr)
		if !ok {
			continue
		}
		a.mu.Lock()
		client := a.client
		a.mu.Unlock()
		if client == nil {
			continue
		}
		a.local.WriteToUDP(buildUDPHeader(ua, buf[:n]), client)
	}
}

func parseUDPHeader(p []byte) (target, []byte, bool) {
	if len(p) < 4 || p[2] != 0 { // parçalı (FRAG) datagramlar desteklenmez
		return target{}, nil, false
	}
	var host string
	i := 4
	switch p[3] {
	case atypV4:
		if len(p) < i+4+2 {
			return target{}, nil, false
		}
		host = netip.AddrFrom4([4]byte(p[i : i+4])).String()
		i += 4
	case atypV6:
		if len(p) < i+16+2 {
			return target{}, nil, false
		}
		host = netip.AddrFrom16([16]byte(p[i : i+16])).Unmap().String()
		i += 16
	case atypDomain:
		if len(p) < i+1 {
			return target{}, nil, false
		}
		l := int(p[i])
		i++
		if len(p) < i+l+2 {
			return target{}, nil, false
		}
		host = string(p[i : i+l])
		i += l
	default:
		return target{}, nil, false
	}
	port := binary.BigEndian.Uint16(p[i : i+2])
	return target{host, port}, p[i+2:], true
}

func buildUDPHeader(from *net.UDPAddr, payload []byte) []byte {
	ip := from.IP.To4()
	atyp := byte(atypV4)
	if ip == nil {
		ip = from.IP.To16()
		atyp = atypV6
	}
	out := make([]byte, 0, 4+len(ip)+2+len(payload))
	out = append(out, 0, 0, 0, atyp)
	out = append(out, ip...)
	out = binary.BigEndian.AppendUint16(out, uint16(from.Port))
	return append(out, payload...)
}
