package main

// iPhotos: photos and videos sent from the phone are imported into Photos.app on this
// Mac, which (with iCloud Photos on) carries them to iCloud. The file is written to an
// inbox folder, handed to Photos.app by AppleScript (the path travels as an osascript
// argument, never inside the script), and deleted once Photos has copied it into its
// library. A ledger of SHA-256 hashes makes every upload idempotent: the phone may send
// the same photo twice (lost reply, reinstall) and it is imported once. Photo contents
// are never logged.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

const photosImportScript = `on run argv
	tell application "Photos"
		set imported to import {POSIX file (item 1 of argv)} skip check duplicates true
		if (count of imported) > 0 then
			return id of item 1 of imported
		end if
		return ""
	end tell
end run`

type photoRecord struct {
	Name       string `json:"name"`
	Size       int64  `json:"size"`
	ImportedAt int64  `json:"imported_at"`
	MediaID    string `json:"media_id,omitempty"`
}

// PhotoStore is the inbox folder plus the ledger of what has been imported.
type PhotoStore struct {
	mu     sync.Mutex
	inbox  string
	ledger string
	have   map[string]photoRecord
}

func newPhotoStore() *PhotoStore {
	dir := configDir()
	p := &PhotoStore{
		inbox:  filepath.Join(dir, "photos-inbox"),
		ledger: filepath.Join(dir, "photos.json"),
		have:   map[string]photoRecord{},
	}
	_ = os.MkdirAll(p.inbox, 0o700)
	if b, err := os.ReadFile(p.ledger); err == nil {
		_ = json.Unmarshal(b, &p.have)
	}
	return p
}

func (p *PhotoStore) save() {
	b, err := json.MarshalIndent(p.have, "", " ")
	if err != nil {
		return
	}
	tmp := p.ledger + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err == nil {
		_ = os.Rename(tmp, p.ledger)
	}
}

func (p *PhotoStore) count() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.have)
}

var unsafeName = regexp.MustCompile(`[^A-Za-z0-9._-]+`)

func safeName(name string) string {
	name = filepath.Base(strings.TrimSpace(name))
	name = unsafeName.ReplaceAllString(name, "_")
	if name == "" || name == "." || name == ".." {
		name = "photo"
	}
	if len(name) > 120 {
		name = name[len(name)-120:]
	}
	return name
}

var shaHex = regexp.MustCompile(`^[0-9a-f]{64}$`)

// importFile hands one file in the inbox to Photos.app and returns the media item id.
func importFile(ctx context.Context, path string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	defer cancel()
	out, err := exec.CommandContext(ctx, "osascript", "-e", photosImportScript, path).CombinedOutput()
	if err != nil {
		s := string(out)
		switch {
		case strings.Contains(s, "-1743"), strings.Contains(s, "Not authorized"):
			return "", errors.New("the Mac hasn't allowed imsg-bridge to control Photos: click OK on the prompt on the Mac, or allow it under Privacy & Security › Automation")
		case ctx.Err() != nil:
			return "", errors.New("Photos on the Mac didn't finish the import in time (maybe a prompt is waiting on the Mac's screen)")
		default:
			return "", errors.New("Photos on the Mac refused the file")
		}
	}
	return strings.TrimSpace(string(out)), nil
}

// photoUpload: POST /v1/photos with the raw file as the body.
// Headers: X-Photo-Name (file name), X-Photo-Sha256 (lowercase hex, the phone's hash).
// Replies {"status":"imported"|"duplicate", ...}.
func (s *Server) photoUpload(w http.ResponseWriter, r *http.Request) {
	name := safeName(r.Header.Get("X-Photo-Name"))
	sha := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Photo-Sha256")))
	if !shaHex.MatchString(sha) {
		writeErr(w, http.StatusBadRequest, "bad_request", "X-Photo-Sha256 must be the file's SHA-256 in hex")
		return
	}
	p := s.photos
	p.mu.Lock()
	rec, dup := p.have[sha]
	p.mu.Unlock()
	if dup {
		writeJSON(w, http.StatusOK, map[string]any{"status": "duplicate", "name": rec.Name, "imported_at": rec.ImportedAt})
		return
	}

	// Stream the body to the inbox while hashing it; a mismatch means a broken transfer.
	path := filepath.Join(p.inbox, sha[:16]+"-"+name)
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "inbox", "couldn't write to the inbox folder on the Mac")
		return
	}
	h := sha256.New()
	size, copyErr := io.Copy(io.MultiWriter(f, h), r.Body)
	closeErr := f.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(path)
		writeErr(w, http.StatusBadRequest, "upload", "the upload stopped before the file was complete")
		return
	}
	if hex.EncodeToString(h.Sum(nil)) != sha {
		_ = os.Remove(path)
		writeErr(w, http.StatusBadRequest, "checksum", "the file arrived damaged (checksum mismatch); the phone will send it again")
		return
	}

	// One import at a time: Photos.app handles them in order and the ledger stays consistent.
	p.mu.Lock()
	defer p.mu.Unlock()
	if rec, dup := p.have[sha]; dup {
		_ = os.Remove(path)
		writeJSON(w, http.StatusOK, map[string]any{"status": "duplicate", "name": rec.Name, "imported_at": rec.ImportedAt})
		return
	}
	mediaID, err := importFile(r.Context(), path)
	if err != nil {
		// Keep the file: the phone retries, and the hash check will skip the transfer... no:
		// a retry re-sends; so remove it and let the next attempt be clean.
		_ = os.Remove(path)
		log.Printf("photos: import failed (%d bytes): %v", size, err)
		writeErr(w, http.StatusBadGateway, "photos_import", err.Error())
		return
	}
	_ = os.Remove(path)
	rec = photoRecord{Name: name, Size: size, ImportedAt: time.Now().UnixMilli(), MediaID: mediaID}
	p.have[sha] = rec
	p.save()
	log.Printf("photos: imported %s (%d bytes)", name, size)
	writeJSON(w, http.StatusOK, map[string]any{"status": "imported", "name": name, "imported_at": rec.ImportedAt, "media_id": mediaID})
}

// photoHave: POST /v1/photos/have {"sha256":[...]} → {"have":[...]} so the phone can
// reconcile after a reinstall without sending anything.
func (s *Server) photoHave(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Sha256 []string `json:"sha256"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "expected {\"sha256\": [...]}")
		return
	}
	p := s.photos
	p.mu.Lock()
	defer p.mu.Unlock()
	have := make([]string, 0, len(req.Sha256))
	for _, x := range req.Sha256 {
		x = strings.ToLower(x)
		if _, ok := p.have[x]; ok {
			have = append(have, x)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"have": have, "total": len(p.have)})
}

// photoStats: GET /v1/photos/stats.
func (s *Server) photoStats(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"imported": s.photos.count()})
}
