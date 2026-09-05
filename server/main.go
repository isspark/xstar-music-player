// xstar-relay: tiny rendezvous + transparent relay for the XSTAR "Listen Together"
// feature. It never parses application messages or stores any media; it only
// (1) pairs the two control sockets of a room and relays bytes between them,
// and (2) tunnels the member's HTTP media requests (stream/artwork) to the
// host's local music server and returns the framed response.
//
// Wire protocol on a single TCP port:
//
//   Control socket : first line  "CTRL <code> host|member <nickname>"
//                    afterwards opaque length-prefixed app frames, relayed.
//   Tunnel socket  : first line  "TUNNEL <code>"
//                    afterwards host pump writes a sequence of
//                    [int32 len][chunk] frames; a zero-length frame ends the
//                    response. This keeps large audio responses streamed.
//   Media request  : first line  "GET|HEAD /room/<code>/... HTTP/1.1"
//                    forwarded to the room tunnel, one framed response back.
//
// Usage: xstar-relay -listen=:9301
package main

import (
	"bufio"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	codeLen    = 6
	maxLineLen = 1024
	maxHdrLen  = 64 * 1024
	maxFrame   = 64 * 1024 * 1024
	waitRoom   = 35 * time.Second
)

type room struct {
	code string

	hostCtrl net.Conn
	hostBR   *bufio.Reader
	member   net.Conn
	memberBR *bufio.Reader

	tunnel   net.Conn
	tunnelBR *bufio.Reader

	mediaMu sync.Mutex

	relayDone    chan struct{}
	relayStarted chan struct{}
	closeOnce    sync.Once
	relayOnce    sync.Once
}

type hub struct {
	mu    sync.Mutex
	rooms map[string]*room
}

func newHub() *hub { return &hub{rooms: make(map[string]*room)} }

func (h *hub) get(code string) *room {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.rooms[code]
}

func (h *hub) put(code string, r *room) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if old, ok := h.rooms[code]; ok {
		old.closeAll("房间被替换")
	}
	h.rooms[code] = r
}

func (h *hub) drop(code string, r *room) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.rooms[code] == r {
		delete(h.rooms, code)
	}
}

func (r *room) closeAll(_ string) {
	r.closeOnce.Do(func() { close(r.relayDone) })
	for _, c := range []net.Conn{r.hostCtrl, r.member, r.tunnel} {
		if c != nil {
			_ = c.Close()
		}
	}
}

func newRoom(h *hub, code string) *room {
	r := &room{code: code, relayDone: make(chan struct{}), relayStarted: make(chan struct{})}
	h.put(code, r)
	go func() {
		select {
		case <-r.relayDone:
			return
		case <-r.relayStarted:
			// Relay is live; teardown is driven by peer disconnects instead.
			return
		case <-time.After(waitRoom):
			h.mu.Lock()
			if h.rooms[code] == r {
				delete(h.rooms, code)
			}
			h.mu.Unlock()
			r.closeAll("等待超时")
		}
	}()
	return r
}

func main() {
	listen := flag.String("listen", ":9301", "TCP listen address")
	flag.Parse()

	h := newHub()
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatalf("listen %s: %v", *listen, err)
	}
	log.Printf("xstar-relay listening on %s", ln.Addr())
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		go h.handleConn(conn)
	}
}

func (h *hub) handleConn(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
	br := bufio.NewReader(conn)
	first, err := readLine(br)
	if err != nil {
		return
	}
	switch {
	case strings.HasPrefix(first, "CTRL "):
		_ = conn.SetDeadline(time.Time{})
		h.handleControl(conn, br, first)
	case strings.HasPrefix(first, "TUNNEL "):
		_ = conn.SetDeadline(time.Time{})
		h.handleTunnel(conn, br, first)
	default:
		_ = conn.SetDeadline(time.Time{})
		h.handleMedia(conn, br, first)
	}
}

func readLine(br *bufio.Reader) (string, error) {
	line, err := br.ReadString('\n')
	if err != nil {
		return "", err
	}
	line = strings.TrimRight(line, "\r\n")
	if len(line) > maxLineLen {
		return "", errors.New("line too long")
	}
	return line, nil
}

func validCode(code string) bool {
	if len(code) != codeLen {
		return false
	}
	for _, c := range code {
		if !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') {
			return false
		}
	}
	return true
}

func fieldsOf(line, prefix string, want int) []string {
	parts := strings.Fields(strings.TrimPrefix(line, prefix))
	if len(parts) < want {
		return nil
	}
	return parts
}

// handleControl registers a control socket as host/member and starts relaying
// once both sides are present.
func (h *hub) handleControl(conn net.Conn, br *bufio.Reader, first string) {
	parts := fieldsOf(first, "CTRL ", 3)
	if parts == nil || !validCode(parts[0]) {
		return
	}
	code, role := parts[0], parts[1]
	if role != "host" && role != "member" {
		return
	}

	r := h.get(code)
	if r == nil {
		r = newRoom(h, code)
	}

	h.mu.Lock()
	if role == "host" {
		if r.hostCtrl != nil {
			h.mu.Unlock()
			return
		}
		r.hostCtrl = conn
		r.hostBR = br
	} else {
		if r.member != nil {
			h.mu.Unlock()
			return
		}
		r.member = conn
		r.memberBR = br
	}
	ready := r.hostCtrl != nil && r.member != nil
	h.mu.Unlock()

	if ready {
		r.startRelay(h, code)
	}

	// Keep this control socket open for the lifetime of the room. handleConn
	// defers conn.Close(), so returning here would immediately tear down the
	// just-registered host/member connection before its peer arrives. Block
	// until the room ends (either peer disconnects, wait window expires, or the
	// session is replaced), mirroring handleTunnel.
	<-r.relayDone
}

func (r *room) startRelay(h *hub, code string) {
	r.relayOnce.Do(func() {
		h.mu.Lock()
		host, member := r.hostCtrl, r.member
		hostBR, memberBR := r.hostBR, r.memberBR
		h.mu.Unlock()
		if host == nil || member == nil {
			return
		}
		_ = host.SetDeadline(time.Time{})
		_ = member.SetDeadline(time.Time{})
		close(r.relayStarted)
		// Peer A -> Peer B. When either side dies the whole session is torn down.
		go func() {
			_, _ = io.Copy(member, hostBR)
			r.teardown(h, code)
		}()
		go func() {
			_, _ = io.Copy(host, memberBR)
			r.teardown(h, code)
		}()
	})
}

func (r *room) teardown(h *hub, code string) {
	h.mu.Lock()
	if h.rooms[code] == r {
		delete(h.rooms, code)
	}
	h.mu.Unlock()
	r.closeAll("会话结束")
}

// handleTunnel keeps the host's HTTP-origin tunnel socket alive until the room
// ends; individual media responses are framed and consumed by handleMedia.
func (h *hub) handleTunnel(conn net.Conn, br *bufio.Reader, first string) {
	parts := fieldsOf(first, "TUNNEL ", 1)
	if parts == nil || !validCode(parts[0]) {
		return
	}
	code := parts[0]
	r := h.get(code)
	if r == nil {
		r = newRoom(h, code)
	}

	h.mu.Lock()
	// A previous tunnel may still be registered if this is a reconnect from the
	// same host after a mid-stream abort; supersede it.
	if old := r.tunnel; old != nil && old != conn {
		_ = old.Close()
	}
	r.tunnel = conn
	r.tunnelBR = br
	h.mu.Unlock()

	<-r.relayDone
	_ = conn.Close()
	h.mu.Lock()
	if r.tunnel == conn {
		r.tunnel = nil
		r.tunnelBR = nil
	}
	h.mu.Unlock()
	h.drop(code, r)
}

func (h *hub) handleMedia(conn net.Conn, br *bufio.Reader, first string) {
	defer conn.Close()

	path := mediaPath(first)
	if path == "" {
		writeHTTP(conn, "400 Bad Request", "bad request")
		return
	}
	code := pathCode(path)
	if !validCode(code) {
		writeHTTP(conn, "404 Not Found", "unknown room")
		return
	}
	header, err := readHeader(br, first)
	if err != nil {
		return
	}

	r := h.get(code)
	if r == nil {
		writeHTTP(conn, "404 Not Found", "room not found")
		return
	}

	r.mediaMu.Lock()
	defer r.mediaMu.Unlock()

	h.mu.Lock()
	tunnel, tunnelBR := r.tunnel, r.tunnelBR
	h.mu.Unlock()
	if tunnel == nil || tunnelBR == nil {
		// The tunnel is briefly down while the host pump reconnects after an
		// aborted response. Give it a short window instead of failing fast, so
		// the member's immediate retry is served instead of hitting a 503.
		deadline := time.Now().Add(4 * time.Second)
		for (tunnel == nil || tunnelBR == nil) && time.Now().Before(deadline) {
			time.Sleep(20 * time.Millisecond)
			h.mu.Lock()
			tunnel, tunnelBR = r.tunnel, r.tunnelBR
			h.mu.Unlock()
		}
		if tunnel == nil || tunnelBR == nil {
			writeHTTP(conn, "503 Service Unavailable", "host tunnel not ready")
			return
		}
	}

	_ = tunnel.SetWriteDeadline(time.Now().Add(15 * time.Second))
	if _, err := tunnel.Write(header); err != nil {
		writeHTTP(conn, "502 Bad Gateway", "tunnel write failed")
		return
	}

	// Read the streamed, zero-terminated framed response from the host pump.
	frameNo := 0
	totalBytes := 0
	for {
		tunnel.SetReadDeadline(time.Now().Add(120 * time.Second))
		resp, err := readFrame(tunnelBR)
		if err != nil {
			writeHTTP(conn, "502 Bad Gateway", "no response from host")
			return
		}
		if len(resp) == 0 {
			break
		}
		frameNo++
		totalBytes += len(resp)
		if frameNo == 1 {
			preview := len(resp)
			if preview > 24 {
				preview = 24
			}
			log.Printf("media code=%s firstFrame len=%d head=%q", code, len(resp), string(resp[:preview]))
		}
		if _, err := conn.Write(resp); err != nil {
			// The member hung up mid-stream. The host pump is single-threaded
			// and would keep streaming the remainder of this response forever,
			// blocking every later media request behind a multi-MB drain. Tear
			// down the tunnel instead so the pump's next write fails and it
			// reconnects, leaving the room ready for the member's next request
			// almost immediately.
			log.Printf("media code=%s member write failed after %d frames %d bytes; closing tunnel for re-establish", code, frameNo, totalBytes)
			h.mu.Lock()
			if r.tunnel == tunnel {
				r.tunnel = nil
				r.tunnelBR = nil
			}
			h.mu.Unlock()
			_ = tunnel.Close()
			return
		}
	}
	log.Printf("media code=%s response complete frames=%d bytes=%d", code, frameNo, totalBytes)
}

func mediaPath(first string) string {
	parts := strings.Split(first, " ")
	if len(parts) < 2 || !strings.HasPrefix(parts[1], "/room/") {
		return ""
	}
	return parts[1]
}

func pathCode(path string) string {
	rest := strings.TrimPrefix(path, "/room/")
	if idx := strings.IndexByte(rest, '/'); idx >= 0 {
		rest = rest[:idx]
	}
	return rest
}

func readHeader(br *bufio.Reader, first string) ([]byte, error) {
	var buf []byte
	buf = append(buf, []byte(first+"\r\n")...)
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return nil, err
		}
		if len(buf)+len(line) > maxHdrLen {
			return nil, errors.New("header too large")
		}
		buf = append(buf, []byte(line)...)
		if line == "\r\n" || line == "\n" {
			return buf, nil
		}
	}
}

func writeHTTP(conn net.Conn, status, body string) {
	payload := fmt.Sprintf("HTTP/1.1 %s\r\nContent-Type: text/plain\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s", status, len(body), body)
	_, _ = conn.Write([]byte(payload))
}

func readFrame(br *bufio.Reader) ([]byte, error) {
	var lenBuf [4]byte
	if _, err := io.ReadFull(br, lenBuf[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(lenBuf[:])
	if n > maxFrame {
		return nil, errors.New("bad frame length " + strconv.Itoa(int(n)))
	}
	if n == 0 {
		return nil, nil
	}
	body := make([]byte, int(n))
	if _, err := io.ReadFull(br, body); err != nil {
		return nil, err
	}
	return body, nil
}
