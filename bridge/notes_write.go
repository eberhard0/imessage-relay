package main

// The only two changes iNotes can make: create a note, and add text to the
// end of a plain note. Both go through Notes.app with AppleScript, never into
// NoteStore.sqlite. Notes.app can only add text by rewriting a note's whole
// body, which turns checklists into bullets and drops attachments, so adding
// is refused for any note that has checklists, attachments or a lock.
// The text travels as osascript arguments, never inside the script source.
// Note text is never written to the log.

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"html"
	"log"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

const notesAppendScript = `on run argv
	tell application "Notes"
		set n to note id (item 1 of argv)
		set body of n to (body of n) & (item 2 of argv)
	end tell
end run`

const notesCreateScript = `on run argv
	tell application "Notes"
		if (item 1 of argv) is "" then
			set n to make new note with properties {body:(item 2 of argv)}
		else
			set n to make new note at folder id (item 1 of argv) with properties {body:(item 2 of argv)}
		end if
		return id of n
	end tell
end run`

var errNotAppendable = errors.New("this note has checklists, attachments or a lock, so text can't be added from the phone")

var notesWriteMu sync.Mutex

// textToHTML turns typed text into Notes' body HTML, one div per line.
func textToHTML(text string, title bool) string {
	var b strings.Builder
	for i, line := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		switch {
		case i == 0 && title:
			b.WriteString("<div><h1>" + html.EscapeString(line) + "</h1></div>")
		case line == "":
			b.WriteString("<div><br></div>")
		default:
			b.WriteString("<div>" + html.EscapeString(line) + "</div>")
		}
	}
	return b.String()
}

// appleScriptIDs returns Notes.app's ids (x-coredata://<store>/<Entity>/p<pk>)
// for a note and its folder.
func (n *NotesStore) appleScriptIDs(db *sql.DB, entity string, pk int64) (string, error) {
	var store string
	if err := db.QueryRow(`SELECT Z_UUID FROM Z_METADATA LIMIT 1`).Scan(&store); err != nil {
		return "", err
	}
	return "x-coredata://" + store + "/" + entity + "/p" + strconv.FormatInt(pk, 10), nil
}

// Appendable reports whether text may be added to a note: unlocked, and its body has
// no checklists or attachments that a rewrite would damage.
func appendable(note *Note) bool {
	if note == nil || note.Locked {
		return false
	}
	for _, b := range note.Blocks {
		if b.Type == "attachment" || b.Type == "check" {
			return false
		}
	}
	return true
}

func runNotesScript(ctx context.Context, script string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 45*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "osascript", append([]string{"-e", script}, args...)...).CombinedOutput()
	if err != nil {
		s := string(out)
		if strings.Contains(s, "-1743") || strings.Contains(s, "Not authorized") {
			return "", errors.New("the Mac hasn't allowed imsg-bridge to control Notes: click OK on the prompt on the Mac, or allow it under Privacy & Security › Automation")
		}
		if ctx.Err() != nil {
			return "", errors.New("Notes on the Mac didn't answer in time (maybe a permission prompt is waiting on the Mac's screen)")
		}
		return "", errors.New("Notes on the Mac refused the change")
	}
	return strings.TrimSpace(string(out)), nil
}

// Append adds text to the end of a plain note.
func (n *NotesStore) Append(ctx context.Context, id, text string) error {
	note, err := n.Note(id)
	if err != nil {
		return err
	}
	if !appendable(note) {
		return errNotAppendable
	}
	var asID string
	err = n.withDB(func(db *sql.DB, e entities) error {
		var pk int64
		if err := db.QueryRow(`SELECT Z_PK FROM ZICCLOUDSYNCINGOBJECT WHERE Z_ENT = ? AND ZIDENTIFIER = ?`, e.note, id).Scan(&pk); err != nil {
			return err
		}
		asID, err = n.appleScriptIDs(db, "ICNote", pk)
		return err
	})
	if err != nil {
		return err
	}
	_, err = runNotesScript(ctx, notesAppendScript, asID, textToHTML(text, false))
	return err
}

// Create makes a new note in a folder (empty = Notes' default folder) and returns its id.
func (n *NotesStore) Create(ctx context.Context, folder, text string) (string, error) {
	var folderID string
	if folder != "" {
		err := n.withDB(func(db *sql.DB, e entities) error {
			var pk int64
			err := db.QueryRow(`SELECT Z_PK FROM ZICCLOUDSYNCINGOBJECT WHERE Z_ENT = ? AND ZIDENTIFIER = ?
				AND IFNULL(ZMARKEDFORDELETION,0) = 0 AND IFNULL(ZFOLDERTYPE,0) <> 1`, e.folder, folder).Scan(&pk)
			if errors.Is(err, sql.ErrNoRows) {
				return errNoteNotFound
			}
			if err != nil {
				return err
			}
			folderID, err = n.appleScriptIDs(db, "ICFolder", pk)
			return err
		})
		if err != nil {
			return "", err
		}
	}
	out, err := runNotesScript(ctx, notesCreateScript, folderID, textToHTML(text, true))
	if err != nil {
		return "", err
	}
	// Notes answers with x-coredata://…/ICNote/p<pk>; wait for the row to get its identifier.
	pk, _ := strconv.ParseInt(out[strings.LastIndex(out, "/p")+2:], 10, 64)
	deadline := time.Now().Add(6 * time.Second)
	for pk > 0 && time.Now().Before(deadline) {
		var ident sql.NullString
		_ = n.withDB(func(db *sql.DB, e entities) error {
			return db.QueryRow(`SELECT ZIDENTIFIER FROM ZICCLOUDSYNCINGOBJECT WHERE Z_PK = ? AND Z_ENT = ?`, pk, e.note).Scan(&ident)
		})
		if ident.Valid && ident.String != "" {
			return ident.String, nil
		}
		time.Sleep(300 * time.Millisecond)
	}
	return "", nil // created; the app finds it on its next refresh
}

// ---- HTTP -------------------------------------------------------------------

type noteWriteRequest struct {
	Folder string `json:"folder"`
	Text   string `json:"text"`
}

func readNoteText(w http.ResponseWriter, r *http.Request) (noteWriteRequest, bool) {
	var req noteWriteRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 128<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "body must be JSON")
		return req, false
	}
	req.Text = strings.TrimRight(req.Text, " \n\t")
	if strings.TrimSpace(req.Text) == "" || len(req.Text) > 50000 {
		writeErr(w, http.StatusBadRequest, "bad_request", "text must be 1–50000 characters")
		return req, false
	}
	return req, true
}

func (s *Server) noteAppend(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !noteIDPattern.MatchString(id) {
		writeErr(w, http.StatusBadRequest, "bad_request", "bad note id")
		return
	}
	req, ok := readNoteText(w, r)
	if !ok {
		return
	}
	notesWriteMu.Lock()
	defer notesWriteMu.Unlock()
	if err := s.notes.Append(r.Context(), id, req.Text); err != nil {
		switch {
		case errors.Is(err, errNoteNotFound):
			writeErr(w, http.StatusNotFound, "not_found", "no such note")
		case errors.Is(err, errNotAppendable):
			writeErr(w, http.StatusConflict, "not_appendable", err.Error())
		default:
			log.Printf("note append failed: %v", err)
			writeErr(w, http.StatusBadGateway, "notes_failed", err.Error())
		}
		return
	}
	log.Printf("added text to a note")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "id": id})
}

func (s *Server) noteCreate(w http.ResponseWriter, r *http.Request) {
	req, ok := readNoteText(w, r)
	if !ok {
		return
	}
	if req.Folder != "" && !noteIDPattern.MatchString(req.Folder) {
		writeErr(w, http.StatusBadRequest, "bad_request", "bad folder id")
		return
	}
	notesWriteMu.Lock()
	defer notesWriteMu.Unlock()
	id, err := s.notes.Create(r.Context(), req.Folder, req.Text)
	if err != nil {
		if errors.Is(err, errNoteNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "no such folder")
			return
		}
		log.Printf("note create failed: %v", err)
		writeErr(w, http.StatusBadGateway, "notes_failed", err.Error())
		return
	}
	log.Printf("created a note")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "id": id})
}
