// imsg-bridge serves the Mac's iMessages (and, read-only, Apple Notes) to the Android apps on Android over
// Tailscale. It reads ~/Library/Messages/chat.db read-only and sends through
// Messages.app with AppleScript. It listens on the tailnet address only and
// every request needs the bearer token created by `imsg-bridge init`.
//
// Message bodies are never written to the log.
package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

var version = "dev"

const (
	agentLabel   = "com.eberhard.imsg-bridge"
	pollInterval = 2 * time.Second
	// The backfill guard for live events: only messages dated within this window.
	liveWindow = 10 * time.Minute
	// How far back a reconnecting app may catch up.
	replayWindow = 24 * time.Hour
)

type Config struct {
	Listen string `json:"listen"`
	Token  string `json:"token"`
}

func home() string {
	h, err := os.UserHomeDir()
	if err != nil {
		log.Fatal(err)
	}
	return h
}

func configDir() string { return filepath.Join(home(), "Library", "Application Support", "imsg-bridge") }
func configPath() string { return filepath.Join(configDir(), "config.json") }

func loadConfig() (*Config, error) {
	b, err := os.ReadFile(configPath())
	if err != nil {
		return nil, fmt.Errorf("no config — run `imsg-bridge init` first (%v)", err)
	}
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return nil, err
	}
	if c.Token == "" || c.Listen == "" {
		return nil, errors.New("config is missing listen or token — run `imsg-bridge init --rotate`")
	}
	return &c, nil
}

func newToken() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		log.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// checkListen refuses anything but a Tailscale (100.64.0.0/10) or loopback
// address: the bridge must never listen on 0.0.0.0 or a LAN.
func checkListen(addr string) error {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return err
	}
	ip := net.ParseIP(host)
	_, cgnat, _ := net.ParseCIDR("100.64.0.0/10")
	if ip == nil || !(cgnat.Contains(ip) || ip.IsLoopback()) {
		return fmt.Errorf("listen address %s is not a Tailscale (100.x) or loopback address", host)
	}
	return nil
}

func main() {
	log.SetFlags(log.LstdFlags)
	cmd := "serve"
	args := os.Args[1:]
	if len(args) > 0 && !strings.HasPrefix(args[0], "-") {
		cmd, args = args[0], args[1:]
	}
	switch cmd {
	case "init":
		cmdInit(args)
	case "token":
		c, err := loadConfig()
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(c.Token)
	case "serve":
		cmdServe(args)
	case "check":
		cmdCheck()
	case "notes-check":
		// Notes only: no Messages or Automation probe, safe to run next to the live bridge.
		notes := &NotesStore{dir: filepath.Join(home(), "Library", "Group Containers", "group.com.apple.notes")}
		fmt.Println("Notes:", notes.SelfTest())
	case "install-agent":
		cmdInstallAgent()
	case "uninstall-agent":
		cmdUninstallAgent()
	case "version":
		fmt.Println(version)
	default:
		fmt.Fprintf(os.Stderr, "usage: imsg-bridge [init [--listen 100.x.y.z:8765] [--rotate] | serve | check | token | install-agent | uninstall-agent | version]\n")
		os.Exit(2)
	}
}

func cmdInit(args []string) {
	fs := flag.NewFlagSet("init", flag.ExitOnError)
	listen := fs.String("listen", "100.90.106.50:8765", "tailnet address:port to listen on")
	rotate := fs.Bool("rotate", false, "replace the token (the app must be given the new one)")
	_ = fs.Parse(args)
	if err := checkListen(*listen); err != nil {
		log.Fatal(err)
	}
	c, err := loadConfig()
	if err != nil {
		c = &Config{}
	}
	c.Listen = *listen
	if c.Token == "" || *rotate {
		c.Token = newToken()
	}
	if err := os.MkdirAll(configDir(), 0o700); err != nil {
		log.Fatal(err)
	}
	b, _ := json.MarshalIndent(c, "", "  ")
	if err := os.WriteFile(configPath(), b, 0o600); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("Config: %s\nListen: %s\nToken (enter it once in the Relay app):\n\n  %s\n\n", configPath(), c.Listen, c.Token)
}

func cmdCheck() {
	store := &Store{path: filepath.Join(home(), "Library", "Messages", "chat.db")}
	if id, err := store.MaxRowID(); err != nil {
		fmt.Println("chat.db:     NOT readable —", err)
	} else {
		fmt.Println("chat.db:     readable, newest message", id)
	}
	auto := &Automation{}
	fmt.Println("Automation:  asking Messages (approve the prompt on the Mac's screen if one appears)…")
	auto.Probe(context.Background())
	fmt.Println("Automation: ", auto.Get())
	c := &Contacts{}
	c.Reload(home())
	fmt.Println("Contacts:   ", c.Count(), "phone numbers and emails")
	notes := &NotesStore{dir: filepath.Join(home(), "Library", "Group Containers", "group.com.apple.notes")}
	if n, _, err := notes.Changes(); err != nil {
		fmt.Println("Notes:       NOT readable —", err)
	} else {
		fmt.Println("Notes:      ", n, "notes readable")
		fmt.Println("Notes:      ", notes.SelfTest())
	}
}

func cmdInstallAgent() {
	exe, err := os.Executable()
	if err != nil {
		log.Fatal(err)
	}
	exe, _ = filepath.EvalSymlinks(exe)
	logPath := filepath.Join(home(), "Library", "Logs", "imsg-bridge.log")
	plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key><string>%s</string>
	<key>ProgramArguments</key><array><string>%s</string><string>serve</string></array>
	<key>RunAtLoad</key><true/>
	<key>KeepAlive</key><true/>
	<key>ProcessType</key><string>Interactive</string>
	<key>StandardOutPath</key><string>%s</string>
	<key>StandardErrorPath</key><string>%s</string>
</dict>
</plist>
`, agentLabel, exe, logPath, logPath)
	dir := filepath.Join(home(), "Library", "LaunchAgents")
	_ = os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, agentLabel+".plist")
	if err := os.WriteFile(path, []byte(plist), 0o644); err != nil {
		log.Fatal(err)
	}
	uid := strconv.Itoa(os.Getuid())
	_ = exec.Command("launchctl", "bootout", "gui/"+uid+"/"+agentLabel).Run()
	out, err := exec.Command("launchctl", "bootstrap", "gui/"+uid, path).CombinedOutput()
	if err != nil {
		fmt.Printf("Wrote %s, but launchctl failed: %s\nLoad it from a Terminal on the Mac (or Screen Sharing):\n  launchctl bootstrap gui/%s %s\n", path, strings.TrimSpace(string(out)), uid, path)
		return
	}
	fmt.Printf("Installed and started %s.\nLog: %s\n", path, logPath)
}

func cmdUninstallAgent() {
	uid := strconv.Itoa(os.Getuid())
	_ = exec.Command("launchctl", "bootout", "gui/"+uid+"/"+agentLabel).Run()
	path := filepath.Join(home(), "Library", "LaunchAgents", agentLabel+".plist")
	_ = os.Remove(path)
	fmt.Println("Stopped and removed", path)
}

// ---- events ---------------------------------------------------------------

// Hub fans new messages out to every connected event stream.
type Hub struct {
	mu      sync.Mutex
	clients map[chan Message]struct{}
}

func (h *Hub) subscribe() chan Message {
	ch := make(chan Message, 64)
	h.mu.Lock()
	h.clients[ch] = struct{}{}
	h.mu.Unlock()
	return ch
}

func (h *Hub) unsubscribe(ch chan Message) {
	h.mu.Lock()
	delete(h.clients, ch)
	h.mu.Unlock()
}

func (h *Hub) publish(m Message) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.clients {
		select {
		case ch <- m:
		default: // a stuck client drops events; it catches up with ?since= on reconnect
		}
	}
}

func (h *Hub) count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.clients)
}

type Server struct {
	cfg        *Config
	store      *Store
	hub        *Hub
	auto       *Automation
	notes      *NotesStore
	sendMu     sync.Mutex
	pollMu     sync.Mutex
	lastRowID  int64
	lastPollOK time.Time
	pollErr    string
}

// poll publishes messages that arrived since the last poll. ROWIDs only grow,
// so the high-water mark moves past backfilled rows too, which the date
// filter in NewSince keeps out of the stream.
func (s *Server) poll() {
	max, err := s.store.MaxRowID()
	s.pollMu.Lock()
	defer s.pollMu.Unlock()
	if err != nil {
		s.pollErr = err.Error()
		return
	}
	s.pollErr = ""
	s.lastPollOK = time.Now()
	if s.lastRowID == 0 {
		s.lastRowID = max
		return
	}
	if max <= s.lastRowID {
		return
	}
	msgs, err := s.store.NewSince(s.lastRowID, time.Now().Add(-liveWindow), 500)
	if err != nil {
		s.pollErr = err.Error()
		return
	}
	for _, m := range msgs {
		s.hub.publish(m)
	}
	if len(msgs) > 0 {
		log.Printf("relayed %d new message(s) to %d stream(s)", len(msgs), s.hub.count())
	}
	s.lastRowID = max
}

func (s *Server) pollLoop(ctx context.Context) {
	t := time.NewTicker(pollInterval)
	defer t.Stop()
	s.poll()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.poll()
		}
	}
}

// ---- HTTP -----------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, map[string]string{"error": code, "message": msg})
}

func (s *Server) auth(next http.Handler) http.Handler {
	want := []byte("Bearer " + s.cfg.Token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := []byte(r.Header.Get("Authorization"))
		if subtle.ConstantTimeCompare(got, want) != 1 {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "missing or wrong token")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func intParam(r *http.Request, name string, def, max int64) int64 {
	v, err := strconv.ParseInt(r.URL.Query().Get(name), 10, 64)
	if err != nil || v < 0 {
		return def
	}
	if max > 0 && v > max {
		return max
	}
	return v
}

func (s *Server) dbError(w http.ResponseWriter, err error) {
	log.Printf("database error: %v", err)
	writeErr(w, http.StatusServiceUnavailable, "db_unreadable", err.Error())
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	_, dbErr := s.store.MaxRowID()
	s.pollMu.Lock()
	lastPoll := s.lastPollOK
	s.pollMu.Unlock()
	resp := map[string]any{
		"ok":            dbErr == nil && s.auto.Get() != "denied",
		"version":       version,
		"db_readable":   dbErr == nil,
		"automation":    s.auto.Get(),
		"contacts":      s.store.contacts.Count(),
		"streams":       s.hub.count(),
		"last_poll":     lastPoll.UnixMilli(),
		"server_time":   time.Now().UnixMilli(),
	}
	if dbErr != nil {
		resp["db_error"] = dbErr.Error()
	}
	// Notes is optional: it doesn't change "ok", which is about messages.
	notesErr := s.notes.Readable()
	resp["notes_db_readable"] = notesErr == nil
	if notesErr != nil {
		resp["notes_error"] = notesErr.Error()
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) chats(w http.ResponseWriter, r *http.Request) {
	chats, err := s.store.Chats(int(intParam(r, "limit", 50, 200)))
	if err != nil {
		s.dbError(w, err)
		return
	}
	if chats == nil {
		chats = []Chat{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"chats": chats})
}

func (s *Server) messages(w http.ResponseWriter, r *http.Request) {
	guid := r.PathValue("guid")
	msgs, err := s.store.History(guid, intParam(r, "before", 0, 0), int(intParam(r, "limit", 50, 200)))
	if err != nil {
		s.dbError(w, err)
		return
	}
	if msgs == nil {
		msgs = []Message{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": msgs})
}

func (s *Server) events(w http.ResponseWriter, r *http.Request) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeErr(w, http.StatusInternalServerError, "no_stream", "streaming unsupported")
		return
	}
	ch := s.hub.subscribe()
	defer s.hub.unsubscribe(ch)

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	send := func(m Message) bool {
		b, _ := json.Marshal(m)
		_, err := fmt.Fprintf(w, "id: %d\nevent: message\ndata: %s\n\n", m.ID, b)
		flusher.Flush()
		return err == nil
	}

	// Catch-up after a reconnect: what arrived after the app's last event.
	since := intParam(r, "since", 0, 0)
	if id, err := strconv.ParseInt(r.Header.Get("Last-Event-ID"), 10, 64); err == nil && id > since {
		since = id
	}
	sent := since
	if since > 0 {
		missed, err := s.store.NewSince(since, time.Now().Add(-replayWindow), 200)
		if err == nil {
			for _, m := range missed {
				if !send(m) {
					return
				}
				sent = m.ID
			}
		}
	}
	fmt.Fprint(w, ": ready\n\n")
	flusher.Flush()

	heartbeat := time.NewTicker(15 * time.Second)
	defer heartbeat.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case m := <-ch:
			if m.ID <= sent {
				continue // already sent during catch-up
			}
			if !send(m) {
				return
			}
		case <-heartbeat.C:
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

type sendRequest struct {
	ChatGUID string `json:"chat_guid"`
	Handle   string `json:"handle"`
	Text     string `json:"text"`
}

func (s *Server) send(w http.ResponseWriter, r *http.Request) {
	var req sendRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "body must be JSON")
		return
	}
	req.Text = strings.TrimRight(req.Text, " \n\t")
	if req.Text == "" || len(req.Text) > 20000 {
		writeErr(w, http.StatusBadRequest, "bad_request", "text must be 1–20000 characters")
		return
	}
	target, mode := req.ChatGUID, "chat"
	if (req.ChatGUID == "") == (req.Handle == "") {
		writeErr(w, http.StatusBadRequest, "bad_request", "give exactly one of chat_guid or handle")
		return
	}
	if target == "" {
		target, mode = req.Handle, "handle"
	}

	// One send at a time, so the confirmation below reads the right row.
	s.sendMu.Lock()
	defer s.sendMu.Unlock()
	start := time.Now().Add(-2 * time.Second)
	if err := runSend(r.Context(), s.auto, req.Text, target, mode); err != nil {
		log.Printf("send failed: %v", err)
		switch {
		case errors.Is(err, errAutomationDenied):
			writeErr(w, http.StatusServiceUnavailable, "automation_denied", err.Error())
		case errors.Is(err, errNoSuchChat):
			writeErr(w, http.StatusNotFound, "no_such_chat", err.Error())
		default:
			writeErr(w, http.StatusBadGateway, "send_failed", err.Error())
		}
		return
	}

	chatGUID := req.ChatGUID
	if chatGUID == "" {
		chatGUID, _ = s.store.ChatForHandle(req.Handle)
	}
	// Confirm from chat.db: the newest sent row for this chat, error 0 = sent.
	if chatGUID != "" {
		deadline := time.Now().Add(8 * time.Second)
		for time.Now().Before(deadline) {
			m, err := s.store.LatestFromMe(chatGUID, start)
			if err == nil && m != nil {
				if m.Error != 0 {
					writeErr(w, http.StatusBadGateway, "send_failed", fmt.Sprintf("Messages reported error %d", m.Error))
					return
				}
				writeJSON(w, http.StatusOK, map[string]any{"ok": true, "confirmed": true, "message": m})
				return
			}
			time.Sleep(500 * time.Millisecond)
		}
	}
	log.Printf("send handed to Messages, not yet confirmed")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "confirmed": false})
}

func (s *Server) attachment(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "bad attachment id")
		return
	}
	path, mime, name, err := s.store.AttachmentFile(id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "no such attachment")
		return
	}
	h := home()
	if strings.HasPrefix(path, "~/") {
		path = filepath.Join(h, path[2:])
	}
	path = filepath.Clean(path)
	root := filepath.Join(h, "Library", "Messages", "Attachments") + string(filepath.Separator)
	if !strings.HasPrefix(path, root) {
		writeErr(w, http.StatusForbidden, "forbidden", "attachment is outside the Messages folder")
		return
	}
	ext := strings.ToLower(filepath.Ext(path))
	if ext == ".heic" || ext == ".heif" || mime == "image/heic" || mime == "image/heif" {
		cache := filepath.Join(home(), "Library", "Caches", "imsg-bridge")
		_ = os.MkdirAll(cache, 0o700)
		out := filepath.Join(cache, fmt.Sprintf("%d.jpg", id))
		if _, err := os.Stat(out); err != nil {
			if b, err := exec.CommandContext(r.Context(), "sips", "-s", "format", "jpeg", path, "--out", out).CombinedOutput(); err != nil {
				log.Printf("sips failed for attachment %d: %s", id, strings.TrimSpace(string(b)))
				writeErr(w, http.StatusInternalServerError, "convert_failed", "couldn't convert HEIC to JPEG")
				return
			}
		}
		path, mime = out, "image/jpeg"
		name = strings.TrimSuffix(name, filepath.Ext(name)) + ".jpg"
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_found", "attachment file is missing (maybe still in iCloud)")
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "io", err.Error())
		return
	}
	if mime != "" {
		w.Header().Set("Content-Type", mime)
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=%q", name))
	http.ServeContent(w, r, name, st.ModTime(), f)
}

func cmdServe(args []string) {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	noCaffeinate := fs.Bool("no-caffeinate", false, "let the Mac sleep on AC power (the relay stops while it sleeps)")
	_ = fs.Parse(args)

	cfg, err := loadConfig()
	if err != nil {
		log.Fatal(err)
	}
	if err := checkListen(cfg.Listen); err != nil {
		log.Fatal(err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	contacts := &Contacts{}
	s := &Server{
		cfg:   cfg,
		store: &Store{path: filepath.Join(home(), "Library", "Messages", "chat.db"), contacts: contacts},
		hub:   &Hub{clients: map[chan Message]struct{}{}},
		auto:  &Automation{},
		notes: &NotesStore{dir: filepath.Join(home(), "Library", "Group Containers", "group.com.apple.notes")},
	}

	if !*noCaffeinate {
		// Keeps the Mac awake on AC power for as long as this process lives.
		c := exec.Command("caffeinate", "-s", "-w", strconv.Itoa(os.Getpid()))
		if err := c.Start(); err != nil {
			log.Printf("caffeinate: %v", err)
		}
	}

	go func() {
		contacts.Reload(home())
		log.Printf("contacts: %d phone numbers and emails", contacts.Count())
		t := time.NewTicker(10 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				contacts.Reload(home())
			}
		}
	}()
	go s.auto.Probe(ctx)
	go s.pollLoop(ctx)

	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/health", s.health)
	mux.HandleFunc("GET /v1/chats", s.chats)
	mux.HandleFunc("GET /v1/chats/{guid}/messages", s.messages)
	mux.HandleFunc("GET /v1/events", s.events)
	mux.HandleFunc("POST /v1/send", s.send)
	mux.HandleFunc("GET /v1/attachments/{id}", s.attachment)
	// Apple Notes for the iNotes app: reading, plus new notes and adding to plain notes.
	mux.HandleFunc("GET /v1/notes", s.noteList)
	mux.HandleFunc("GET /v1/notes/folders", s.noteFolders)
	mux.HandleFunc("GET /v1/notes/changes", s.noteChanges)
	mux.HandleFunc("GET /v1/notes/{id}", s.noteGet)
	mux.HandleFunc("GET /v1/notes/attachments/{id}", s.noteAttachment)
	mux.HandleFunc("POST /v1/notes", s.noteCreate)
	mux.HandleFunc("POST /v1/notes/{id}/append", s.noteAppend)

	srv := &http.Server{Handler: s.auth(mux), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutdown)
	}()

	// The tailnet address appears only once Tailscale is up (after boot or
	// wake), so keep trying instead of exiting.
	var ln net.Listener
	for {
		ln, err = net.Listen("tcp", cfg.Listen)
		if err == nil {
			break
		}
		log.Printf("waiting for %s (is Tailscale up?): %v", cfg.Listen, err)
		select {
		case <-ctx.Done():
			return
		case <-time.After(10 * time.Second):
		}
	}
	log.Printf("imsg-bridge %s listening on %s", version, cfg.Listen)
	if err := srv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Fatal(err)
	}
}
