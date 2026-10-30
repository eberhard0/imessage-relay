package main

// Read-only access to Apple Notes for the iNotes app. NoteStore.sqlite is
// opened with mode=ro and a fresh connection per request, like chat.db: Notes
// writes through the WAL and iCloud would sync any damage everywhere, so this
// file never writes to it. Password-protected notes stay locked: only their
// title is listed. Note text is never written to the log.

import (
	"bytes"
	"compress/gzip"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
)

// Notes stores dates as seconds (float) since 2001-01-01.
func notesToUnixMs(d sql.NullFloat64) int64 {
	if !d.Valid || d.Float64 == 0 {
		return 0
	}
	return int64((d.Float64 + appleEpoch) * 1000)
}

type NotesStore struct {
	dir string // ~/Library/Group Containers/group.com.apple.notes
}

func (n *NotesStore) open() (*sql.DB, error) {
	path := filepath.Join(n.dir, "NoteStore.sqlite")
	dsn := "file:" + (&url.URL{Path: path}).EscapedPath() + "?mode=ro&_pragma=busy_timeout(3000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	return db, nil
}

// entities maps Core Data entity names to their Z_ENT numbers, which differ
// between macOS versions.
type entities struct{ note, folder, attachment, media int }

func loadEntities(db *sql.DB) (entities, error) {
	var e entities
	rows, err := db.Query(`SELECT Z_ENT, Z_NAME FROM Z_PRIMARYKEY WHERE Z_NAME IN ('ICNote','ICFolder','ICAttachment','ICMedia')`)
	if err != nil {
		return e, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int
		var name string
		if err := rows.Scan(&id, &name); err != nil {
			return e, err
		}
		switch name {
		case "ICNote":
			e.note = id
		case "ICFolder":
			e.folder = id
		case "ICAttachment":
			e.attachment = id
		case "ICMedia":
			e.media = id
		}
	}
	if e.note == 0 || e.folder == 0 || e.attachment == 0 {
		return e, errors.New("NoteStore.sqlite has an unexpected layout")
	}
	return e, rows.Err()
}

// withDB opens the store and looks up the entity numbers.
func (n *NotesStore) withDB(fn func(*sql.DB, entities) error) error {
	db, err := n.open()
	if err != nil {
		return err
	}
	defer db.Close()
	e, err := loadEntities(db)
	if err != nil {
		return err
	}
	return fn(db, e)
}

// A note is shown when it isn't deleted, sits in a folder, and that folder
// isn't Recently Deleted (ZFOLDERTYPE 1).
const visibleNote = `n.Z_ENT = ? AND IFNULL(n.ZMARKEDFORDELETION,0) = 0 AND n.ZFOLDER IS NOT NULL
	AND f.Z_ENT = ? AND IFNULL(f.ZMARKEDFORDELETION,0) = 0 AND IFNULL(f.ZFOLDERTYPE,0) <> 1`

// ---- folders and lists ------------------------------------------------------

type NoteFolder struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Parent string `json:"parent,omitempty"`
	Count  int    `json:"count"`
}

func (n *NotesStore) Folders() ([]NoteFolder, error) {
	var out []NoteFolder
	err := n.withDB(func(db *sql.DB, e entities) error {
		rows, err := db.Query(`
			SELECT f.ZIDENTIFIER, IFNULL(f.ZTITLE2,''), IFNULL(p.ZIDENTIFIER,''),
			       (SELECT COUNT(*) FROM ZICCLOUDSYNCINGOBJECT n WHERE n.Z_ENT = ? AND n.ZFOLDER = f.Z_PK
			          AND IFNULL(n.ZMARKEDFORDELETION,0) = 0)
			FROM ZICCLOUDSYNCINGOBJECT f
			LEFT JOIN ZICCLOUDSYNCINGOBJECT p ON p.Z_PK = f.ZPARENT
			WHERE f.Z_ENT = ? AND IFNULL(f.ZMARKEDFORDELETION,0) = 0 AND IFNULL(f.ZFOLDERTYPE,0) <> 1
			  AND f.ZIDENTIFIER IS NOT NULL`, e.note, e.folder)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var f NoteFolder
			if err := rows.Scan(&f.ID, &f.Name, &f.Parent, &f.Count); err != nil {
				return err
			}
			out = append(out, f)
		}
		return rows.Err()
	})
	sort.SliceStable(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out, err
}

type NoteSummary struct {
	ID             string `json:"id"`
	Title          string `json:"title"`
	Snippet        string `json:"snippet"`
	Folder         string `json:"folder"`
	Created        int64  `json:"created"`  // Unix ms
	Modified       int64  `json:"modified"` // Unix ms
	Pinned         bool   `json:"pinned"`
	Locked         bool   `json:"locked"`
	HasAttachments bool   `json:"has_attachments"`
}

// List returns notes newest first. before is a Unix-ms modification date for paging.
func (n *NotesStore) List(folder, q string, before int64, limit int) ([]NoteSummary, error) {
	var out []NoteSummary
	err := n.withDB(func(db *sql.DB, e entities) error {
		query := `
			SELECT n.ZIDENTIFIER, IFNULL(n.ZTITLE1,''), IFNULL(n.ZSNIPPET,''), f.ZIDENTIFIER,
			       n.ZCREATIONDATE3, n.ZMODIFICATIONDATE1, IFNULL(n.ZISPINNED,0), IFNULL(n.ZISPASSWORDPROTECTED,0),
			       EXISTS (SELECT 1 FROM ZICCLOUDSYNCINGOBJECT a WHERE a.Z_ENT = ? AND a.ZNOTE = n.Z_PK)
			FROM ZICCLOUDSYNCINGOBJECT n
			JOIN ZICCLOUDSYNCINGOBJECT f ON f.Z_PK = n.ZFOLDER
			WHERE ` + visibleNote + ` AND n.ZIDENTIFIER IS NOT NULL`
		args := []any{e.attachment, e.note, e.folder}
		if folder != "" {
			query += ` AND f.ZIDENTIFIER = ?`
			args = append(args, folder)
		}
		if q != "" {
			// Locked notes are matched by title only; their snippet isn't shown either.
			query += ` AND (n.ZTITLE1 LIKE ? ESCAPE '\' OR (IFNULL(n.ZISPASSWORDPROTECTED,0) = 0 AND n.ZSNIPPET LIKE ? ESCAPE '\'))`
			like := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(q) + "%"
			args = append(args, like, like)
		}
		if before > 0 {
			query += ` AND n.ZMODIFICATIONDATE1 < ?`
			args = append(args, float64(before)/1000-appleEpoch)
		}
		query += ` ORDER BY n.ZMODIFICATIONDATE1 DESC LIMIT ?`
		args = append(args, limit)
		rows, err := db.Query(query, args...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var s NoteSummary
			var created, modified sql.NullFloat64
			if err := rows.Scan(&s.ID, &s.Title, &s.Snippet, &s.Folder, &created, &modified, &s.Pinned, &s.Locked, &s.HasAttachments); err != nil {
				return err
			}
			s.Created, s.Modified = notesToUnixMs(created), notesToUnixMs(modified)
			if s.Locked {
				s.Snippet = ""
			}
			if s.Title == "" {
				s.Title = "New Note"
			}
			out = append(out, s)
		}
		return rows.Err()
	})
	return out, err
}

// Changes is a cheap fingerprint: the app refreshes its list when it moves.
func (n *NotesStore) Changes() (count int, latest int64, err error) {
	err = n.withDB(func(db *sql.DB, e entities) error {
		var m sql.NullFloat64
		if err := db.QueryRow(`SELECT COUNT(*), MAX(n.ZMODIFICATIONDATE1) FROM ZICCLOUDSYNCINGOBJECT n
			JOIN ZICCLOUDSYNCINGOBJECT f ON f.Z_PK = n.ZFOLDER WHERE `+visibleNote, e.note, e.folder).Scan(&count, &m); err != nil {
			return err
		}
		latest = notesToUnixMs(m)
		return nil
	})
	return
}

// Readable is the check behind /v1/health.
func (n *NotesStore) Readable() error {
	return n.withDB(func(*sql.DB, entities) error { return nil })
}

// ---- one note ---------------------------------------------------------------

type Span struct {
	Text      string `json:"text"`
	Bold      bool   `json:"bold,omitempty"`
	Italic    bool   `json:"italic,omitempty"`
	Underline bool   `json:"underline,omitempty"`
	Strike    bool   `json:"strike,omitempty"`
	Link      string `json:"link,omitempty"`
}

// Block is one paragraph or one attachment.
// Type: title, heading, subheading, text, mono, bullet, dash, number, check, attachment.
type Block struct {
	Type   string `json:"type"`
	Spans  []Span `json:"spans,omitempty"`
	Indent int    `json:"indent,omitempty"`
	Done   bool   `json:"done,omitempty"`
	Quote  bool   `json:"quote,omitempty"`
	// Attachments only.
	Attachment *NoteAttachment `json:"attachment,omitempty"`
}

type NoteAttachment struct {
	ID string `json:"id"`
	// Kind: image (shown inline), pdf, file, table, link, other.
	Kind  string `json:"kind"`
	UTI   string `json:"uti"`
	Name  string `json:"name,omitempty"`
	URL   string `json:"url,omitempty"`
	Label string `json:"label"` // what to show when there is no picture, e.g. "Table"
}

type Note struct {
	NoteSummary
	Blocks []Block `json:"blocks"`
}

var errNoteNotFound = errors.New("no such note")

type attRow struct {
	pk                        int64
	id, uti, title, url       string
	mediaID, mediaFile        string
	parent                    int64
}

func (n *NotesStore) Note(id string) (*Note, error) {
	var note *Note
	err := n.withDB(func(db *sql.DB, e entities) error {
		var s NoteSummary
		var pk int64
		var created, modified sql.NullFloat64
		var data []byte
		err := db.QueryRow(`
			SELECT n.Z_PK, n.ZIDENTIFIER, IFNULL(n.ZTITLE1,''), IFNULL(n.ZSNIPPET,''), f.ZIDENTIFIER,
			       n.ZCREATIONDATE3, n.ZMODIFICATIONDATE1, IFNULL(n.ZISPINNED,0), IFNULL(n.ZISPASSWORDPROTECTED,0),
			       (SELECT d.ZDATA FROM ZICNOTEDATA d WHERE d.Z_PK = n.ZNOTEDATA)
			FROM ZICCLOUDSYNCINGOBJECT n
			JOIN ZICCLOUDSYNCINGOBJECT f ON f.Z_PK = n.ZFOLDER
			WHERE `+visibleNote+` AND n.ZIDENTIFIER = ?`, e.note, e.folder, id).
			Scan(&pk, &s.ID, &s.Title, &s.Snippet, &s.Folder, &created, &modified, &s.Pinned, &s.Locked, &data)
		if errors.Is(err, sql.ErrNoRows) {
			return errNoteNotFound
		}
		if err != nil {
			return err
		}
		s.Created, s.Modified = notesToUnixMs(created), notesToUnixMs(modified)
		if s.Title == "" {
			s.Title = "New Note"
		}
		note = &Note{NoteSummary: s, Blocks: []Block{}}
		if s.Locked {
			s.Snippet = ""
			note.NoteSummary = s
			return nil // locked notes stay locked
		}
		atts, err := n.attachments(db, e, pk)
		if err != nil {
			return err
		}
		blocks, err := renderNote(data, func(attID string) []Block { return n.attachmentBlocks(atts, attID) })
		if err != nil {
			log.Printf("note body could not be decoded: %v", err) // the error never contains text
			blocks = []Block{{Type: "text", Spans: []Span{{Text: s.Snippet}}}}
		}
		note.Blocks = blocks
		return nil
	})
	return note, err
}

func (n *NotesStore) attachments(db *sql.DB, e entities, notePK int64) (map[string]attRow, error) {
	rows, err := db.Query(`
		SELECT a.Z_PK, a.ZIDENTIFIER, IFNULL(a.ZTYPEUTI,''), IFNULL(a.ZTITLE,''), IFNULL(a.ZURLSTRING,''),
		       IFNULL(m.ZIDENTIFIER,''), IFNULL(m.ZFILENAME,''), IFNULL(a.ZPARENTATTACHMENT,0)
		FROM ZICCLOUDSYNCINGOBJECT a
		LEFT JOIN ZICCLOUDSYNCINGOBJECT m ON m.Z_PK = a.ZMEDIA
		WHERE a.Z_ENT = ? AND IFNULL(a.ZMARKEDFORDELETION,0) = 0 AND a.ZIDENTIFIER IS NOT NULL
		  AND (a.ZNOTE = ? OR a.ZPARENTATTACHMENT IN (SELECT Z_PK FROM ZICCLOUDSYNCINGOBJECT WHERE Z_ENT = ? AND ZNOTE = ?))`,
		e.attachment, notePK, e.attachment, notePK)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]attRow{}
	for rows.Next() {
		var a attRow
		if err := rows.Scan(&a.pk, &a.id, &a.uti, &a.title, &a.url, &a.mediaID, &a.mediaFile, &a.parent); err != nil {
			return nil, err
		}
		out[a.id] = a
	}
	return out, rows.Err()
}

// attachmentBlocks turns one inline attachment into blocks. A gallery (a
// multi-page scan) becomes one image block per page.
func (n *NotesStore) attachmentBlocks(atts map[string]attRow, id string) []Block {
	a, ok := atts[id]
	if !ok {
		return nil // inline tags and mentions live elsewhere; nothing to show
	}
	if a.uti == "com.apple.notes.gallery" {
		var pages []attRow
		for _, c := range atts {
			if c.parent == a.pk {
				pages = append(pages, c)
			}
		}
		sort.Slice(pages, func(i, j int) bool { return pages[i].pk < pages[j].pk })
		var out []Block
		for _, p := range pages {
			out = append(out, Block{Type: "attachment", Attachment: n.describe(p)})
		}
		if len(out) > 0 {
			return out
		}
	}
	return []Block{{Type: "attachment", Attachment: n.describe(a)}}
}

func (n *NotesStore) describe(a attRow) *NoteAttachment {
	d := &NoteAttachment{ID: a.id, UTI: a.uti, Name: a.title, Kind: "other", Label: "Attachment"}
	if d.Name == "" {
		d.Name = a.mediaFile
	}
	switch a.uti {
	case "com.apple.notes.table":
		d.Kind, d.Label = "table", "Table (open it on the Mac or iPhone)"
		return d
	case "public.url":
		d.Kind, d.Label, d.URL = "link", "Link", a.url
		return d
	}
	path, mime := n.attachmentFile(a)
	switch {
	case strings.HasPrefix(mime, "image/"):
		d.Kind, d.Label = "image", "Image"
	case mime == "application/pdf":
		d.Kind, d.Label = "pdf", "PDF"
	case path != "":
		d.Kind, d.Label = "file", "File"
	}
	switch {
	case strings.Contains(a.uti, "drawing") || strings.HasPrefix(a.uti, "com.apple.paper"):
		if d.Kind != "image" {
			d.Label = "Drawing (open it on the Mac or iPhone)"
		}
	case path == "" && a.mediaID != "":
		d.Label = "Attachment not downloaded on the Mac"
	}
	return d
}

// attachmentFile finds the best file for an attachment: the original media,
// else the rendered image Notes keeps for drawings and scans, else a preview,
// else a rendered PDF. Everything stays inside the Notes container.
func (n *NotesStore) attachmentFile(a attRow) (path, mime string) {
	accounts, _ := filepath.Glob(filepath.Join(n.dir, "Accounts", "*"))
	for _, acc := range accounts {
		if a.mediaID != "" {
			if p := newestFile(filepath.Join(acc, "Media", a.mediaID), a.mediaFile, nil); p != "" {
				return p, mimeFor(p)
			}
		}
		if p := newestFile(filepath.Join(acc, "FallbackImages", a.id), "", imageExt); p != "" {
			return p, mimeFor(p)
		}
		if p := largestPreview(filepath.Join(acc, "Previews"), a.id); p != "" {
			return p, mimeFor(p)
		}
		if p := newestFile(filepath.Join(acc, "FallbackPDFs", a.id), "", map[string]bool{".pdf": true}); p != "" {
			return p, "application/pdf"
		}
	}
	return "", ""
}

var imageExt = map[string]bool{".png": true, ".jpg": true, ".jpeg": true, ".heic": true, ".heif": true, ".gif": true}

// newestFile walks dir for a file named want (any name if empty) with one of exts.
func newestFile(dir, want string, exts map[string]bool) string {
	var best string
	var bestTime int64
	_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if want != "" && d.Name() != want {
			return nil
		}
		if exts != nil && !exts[strings.ToLower(filepath.Ext(p))] {
			return nil
		}
		if info, err := d.Info(); err == nil && info.ModTime().UnixNano() > bestTime {
			best, bestTime = p, info.ModTime().UnixNano()
		}
		return nil
	})
	if best == "" && want != "" {
		return newestFile(dir, "", exts) // the file was renamed; take what is there
	}
	return best
}

// Previews are named <attachment id>-<n>-<w>x<h>-<n>.png, at the top level or in a folder.
func largestPreview(dir, id string) string {
	matches, _ := filepath.Glob(filepath.Join(dir, id+"-*"))
	var best string
	var bestSize int64
	for _, m := range matches {
		p := m
		if st, err := os.Stat(m); err == nil && st.IsDir() {
			if p = newestFile(m, "", imageExt); p == "" {
				continue
			}
		}
		if !imageExt[strings.ToLower(filepath.Ext(p))] {
			continue
		}
		if st, err := os.Stat(p); err == nil && st.Size() > bestSize {
			best, bestSize = p, st.Size()
		}
	}
	return best
}

func mimeFor(p string) string {
	switch strings.ToLower(filepath.Ext(p)) {
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".heic", ".heif":
		return "image/heic"
	case ".gif":
		return "image/gif"
	case ".pdf":
		return "application/pdf"
	case ".mp4", ".mov":
		return "video/mp4"
	}
	return "application/octet-stream"
}

// AttachmentFile resolves an attachment for download, refusing anything that
// belongs to a locked or deleted note.
func (n *NotesStore) AttachmentFile(id string) (path, mime, name string, err error) {
	err = n.withDB(func(db *sql.DB, e entities) error {
		var a attRow
		err := db.QueryRow(`
			SELECT a.Z_PK, a.ZIDENTIFIER, IFNULL(a.ZTYPEUTI,''), IFNULL(a.ZTITLE,''), IFNULL(m.ZIDENTIFIER,''), IFNULL(m.ZFILENAME,'')
			FROM ZICCLOUDSYNCINGOBJECT a
			LEFT JOIN ZICCLOUDSYNCINGOBJECT m ON m.Z_PK = a.ZMEDIA
			LEFT JOIN ZICCLOUDSYNCINGOBJECT pa ON pa.Z_PK = a.ZPARENTATTACHMENT
			JOIN ZICCLOUDSYNCINGOBJECT n ON n.Z_PK = IFNULL(a.ZNOTE, pa.ZNOTE)
			JOIN ZICCLOUDSYNCINGOBJECT f ON f.Z_PK = n.ZFOLDER
			WHERE a.Z_ENT = ? AND a.ZIDENTIFIER = ? AND IFNULL(a.ZMARKEDFORDELETION,0) = 0
			  AND IFNULL(n.ZISPASSWORDPROTECTED,0) = 0 AND `+visibleNote,
			e.attachment, id, e.note, e.folder).Scan(&a.pk, &a.id, &a.uti, &a.title, &a.mediaID, &a.mediaFile)
		if errors.Is(err, sql.ErrNoRows) {
			return errNoteNotFound
		}
		if err != nil {
			return err
		}
		path, mime = n.attachmentFile(a)
		if path == "" {
			return errNoteNotFound
		}
		name = a.mediaFile
		if name == "" {
			name = filepath.Base(path)
		}
		return nil
	})
	return
}

// ---- body decoding ----------------------------------------------------------

// The body is gzip, then protobuf: root.2 (Document) → .3 (Note) → .2 note_text,
// and repeated .5 AttributeRun over note_text in UTF-16 units. The layout
// follows threeplanetssoftware/apple_cloud_notes_parser (MIT), proto/notestore.proto.

type pbField struct {
	num   int
	value uint64
	bytes []byte
}

func pbFields(b []byte) ([]pbField, error) {
	var out []pbField
	for len(b) > 0 {
		key, n := pbVarint(b)
		if n == 0 {
			return nil, errors.New("bad protobuf key")
		}
		b = b[n:]
		f := pbField{num: int(key >> 3)}
		switch key & 7 {
		case 0:
			v, n := pbVarint(b)
			if n == 0 {
				return nil, errors.New("bad varint")
			}
			f.value, b = v, b[n:]
		case 1:
			if len(b) < 8 {
				return nil, errors.New("short fixed64")
			}
			b = b[8:]
		case 2:
			l, n := pbVarint(b)
			if n == 0 || uint64(len(b)-n) < l {
				return nil, errors.New("short bytes")
			}
			f.bytes, b = b[n:n+int(l)], b[n+int(l):]
		case 5:
			if len(b) < 4 {
				return nil, errors.New("short fixed32")
			}
			b = b[4:]
		default:
			return nil, fmt.Errorf("wire type %d", key&7)
		}
		out = append(out, f)
	}
	return out, nil
}

func pbVarint(b []byte) (uint64, int) {
	var v uint64
	for i := 0; i < len(b) && i < 10; i++ {
		v |= uint64(b[i]&0x7f) << (7 * i)
		if b[i] < 0x80 {
			return v, i + 1
		}
	}
	return 0, 0
}

func pbFirst(fs []pbField, num int) (pbField, bool) {
	for _, f := range fs {
		if f.num == num {
			return f, true
		}
	}
	return pbField{}, false
}

type run struct {
	length    int
	style     int // -1 = body text
	indent    int
	checklist bool
	done      bool
	quote     bool
	bold      bool
	italic    bool
	underline bool
	strike    bool
	link      string
	attID     string
}

var styleNames = map[int]string{0: "title", 1: "heading", 2: "subheading", 4: "mono", 100: "bullet", 101: "dash", 102: "number", 103: "check"}

func parseRun(b []byte) (run, error) {
	r := run{style: -1}
	fs, err := pbFields(b)
	if err != nil {
		return r, err
	}
	for _, f := range fs {
		switch f.num {
		case 1:
			r.length = int(f.value)
		case 2: // ParagraphStyle
			ps, err := pbFields(f.bytes)
			if err != nil {
				return r, err
			}
			for _, p := range ps {
				switch p.num {
				case 1:
					r.style = int(p.value)
				case 4:
					r.indent = int(p.value)
				case 5: // Checklist { 1 uuid, 2 done }
					r.checklist = true
					if cl, err := pbFields(p.bytes); err == nil {
						if d, ok := pbFirst(cl, 2); ok {
							r.done = d.value == 1
						}
					}
				case 8:
					r.quote = p.value == 1
				}
			}
		case 5: // font weight: 1 bold, 2 italic, 3 bold italic
			r.bold = f.value == 1 || f.value == 3
			r.italic = f.value == 2 || f.value == 3
		case 6:
			r.underline = f.value == 1
		case 7:
			r.strike = f.value == 1
		case 9:
			r.link = string(f.bytes)
		case 12: // AttachmentInfo { 1 identifier, 2 type_uti }
			if ai, err := pbFields(f.bytes); err == nil {
				if id, ok := pbFirst(ai, 1); ok {
					r.attID = string(id.bytes)
				}
			}
		}
	}
	return r, nil
}

func renderNote(data []byte, attachment func(id string) []Block) ([]Block, error) {
	if len(data) < 2 || data[0] != 0x1f || data[1] != 0x8b {
		return nil, errors.New("body is not gzip")
	}
	zr, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	raw, err := io.ReadAll(io.LimitReader(zr, 64<<20))
	if err != nil {
		return nil, err
	}
	root, err := pbFields(raw)
	if err != nil {
		return nil, err
	}
	doc, ok := pbFirst(root, 2)
	if !ok {
		return nil, errors.New("no document")
	}
	docFields, err := pbFields(doc.bytes)
	if err != nil {
		return nil, err
	}
	noteF, ok := pbFirst(docFields, 3)
	if !ok {
		return nil, errors.New("no note")
	}
	nf, err := pbFields(noteF.bytes)
	if err != nil {
		return nil, err
	}
	textF, _ := pbFirst(nf, 2)
	text := utf16.Encode([]rune(string(textF.bytes)))
	var runs []run
	for _, f := range nf {
		if f.num == 5 {
			r, err := parseRun(f.bytes)
			if err != nil {
				return nil, err
			}
			runs = append(runs, r)
		}
	}
	return buildBlocks(text, runs, attachment), nil
}

// buildBlocks splits the text into paragraphs at newlines; a paragraph takes
// its style from the run of its first character.
func buildBlocks(text []uint16, runs []run, attachment func(string) []Block) []Block {
	blocks := []Block{}
	var cur *Block
	flush := func() {
		if cur != nil {
			blocks = append(blocks, *cur)
			cur = nil
		}
	}
	start := func(r run) {
		t := "text"
		if name, ok := styleNames[r.style]; ok {
			t = name
		}
		if r.checklist {
			t = "check"
		}
		cur = &Block{Type: t, Indent: r.indent, Done: r.done, Quote: r.quote}
	}
	pos := 0
	for _, r := range runs {
		end := pos + r.length
		if end > len(text) {
			end = len(text)
		}
		seg := text[pos:end]
		pos = end
		for len(seg) > 0 {
			if cur == nil {
				start(r)
			}
			if seg[0] == 0xFFFC {
				// The attachment sits in its own paragraph between the text around it.
				textBefore := len(cur.Spans) > 0
				if textBefore {
					flush()
				}
				blocks = append(blocks, attachment(r.attID)...)
				cur = nil
				seg = seg[1:]
				if !textBefore && len(seg) > 0 && seg[0] == '\n' {
					seg = seg[1:]
				}
				continue
			}
			i := 0
			for i < len(seg) && seg[i] != '\n' && seg[i] != 0xFFFC {
				i++
			}
			if i > 0 {
				cur.Spans = append(cur.Spans, Span{
					Text: string(utf16.Decode(seg[:i])), Bold: r.bold, Italic: r.italic,
					Underline: r.underline, Strike: r.strike, Link: r.link,
				})
			}
			seg = seg[i:]
			if len(seg) > 0 && seg[0] == '\n' {
				if cur != nil && len(cur.Spans) == 0 {
					cur.Spans = []Span{} // an empty line keeps its place
				}
				flush()
				seg = seg[1:]
			}
		}
	}
	flush()
	return mergeSpans(blocks)
}

// mergeSpans joins neighbouring spans with the same formatting.
func mergeSpans(blocks []Block) []Block {
	for i := range blocks {
		var out []Span
		for _, s := range blocks[i].Spans {
			if k := len(out) - 1; k >= 0 {
				p := out[k]
				if p.Bold == s.Bold && p.Italic == s.Italic && p.Underline == s.Underline && p.Strike == s.Strike && p.Link == s.Link {
					out[k].Text += s.Text
					continue
				}
			}
			out = append(out, s)
		}
		if blocks[i].Type != "attachment" && out == nil {
			out = []Span{}
		}
		blocks[i].Spans = out
	}
	return blocks
}

// ---- HTTP -------------------------------------------------------------------

var noteIDPattern = regexp.MustCompile(`^[A-Za-z0-9-]{8,64}$`)

func (s *Server) notesError(w http.ResponseWriter, err error) {
	if errors.Is(err, errNoteNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "no such note")
		return
	}
	log.Printf("notes database error: %v", err)
	writeErr(w, http.StatusServiceUnavailable, "notes_unreadable", err.Error())
}

func (s *Server) noteFolders(w http.ResponseWriter, r *http.Request) {
	folders, err := s.notes.Folders()
	if err != nil {
		s.notesError(w, err)
		return
	}
	if folders == nil {
		folders = []NoteFolder{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"folders": folders})
}

func (s *Server) noteList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	notes, err := s.notes.List(q.Get("folder"), strings.TrimSpace(q.Get("q")), intParam(r, "before", 0, 0), int(intParam(r, "limit", 50, 5000)))
	if err != nil {
		s.notesError(w, err)
		return
	}
	if notes == nil {
		notes = []NoteSummary{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"notes": notes})
}

func (s *Server) noteChanges(w http.ResponseWriter, r *http.Request) {
	count, latest, err := s.notes.Changes()
	if err != nil {
		s.notesError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"count": count, "latest": latest, "etag": strconv.Itoa(count) + "-" + strconv.FormatInt(latest, 10)})
}

func (s *Server) noteGet(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !noteIDPattern.MatchString(id) {
		writeErr(w, http.StatusBadRequest, "bad_request", "bad note id")
		return
	}
	note, err := s.notes.Note(id)
	if err != nil {
		s.notesError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, note)
}

func (s *Server) noteAttachment(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !noteIDPattern.MatchString(id) {
		writeErr(w, http.StatusBadRequest, "bad_request", "bad attachment id")
		return
	}
	path, mime, name, err := s.notes.AttachmentFile(id)
	if err != nil {
		s.notesError(w, err)
		return
	}
	// Serve only from inside the Notes container, symlinks resolved.
	resolved, err := filepath.EvalSymlinks(path)
	root, _ := filepath.EvalSymlinks(s.notes.dir)
	if err != nil || !strings.HasPrefix(resolved, root+string(filepath.Separator)) {
		writeErr(w, http.StatusForbidden, "forbidden", "attachment is outside the Notes folder")
		return
	}
	if mime == "image/heic" {
		cache := filepath.Join(home(), "Library", "Caches", "imsg-bridge", "notes")
		_ = os.MkdirAll(cache, 0o700)
		out := filepath.Join(cache, id+".jpg")
		if _, err := os.Stat(out); err != nil {
			if b, err := exec.CommandContext(r.Context(), "sips", "-s", "format", "jpeg", resolved, "--out", out).CombinedOutput(); err != nil {
				log.Printf("sips failed for a note attachment: %s", strings.TrimSpace(string(b)))
				writeErr(w, http.StatusInternalServerError, "convert_failed", "couldn't convert HEIC to JPEG")
				return
			}
		}
		resolved, mime = out, "image/jpeg"
		name = strings.TrimSuffix(name, filepath.Ext(name)) + ".jpg"
	}
	f, err := os.Open(resolved)
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
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Content-Disposition", fmt.Sprintf("inline; filename=%q", name))
	http.ServeContent(w, r, name, st.ModTime(), f)
}

// SelfTest renders every visible note and reports counts only (for `imsg-bridge check`).
func (n *NotesStore) SelfTest() string {
	notes, err := n.List("", "", 0, 100000)
	if err != nil {
		return "list failed: " + err.Error()
	}
	var ok, locked, failed, blocks, images, missing int
	for _, s := range notes {
		if s.Locked {
			locked++
			continue
		}
		note, err := n.Note(s.ID)
		if err != nil {
			failed++
			continue
		}
		ok++
		blocks += len(note.Blocks)
		for _, b := range note.Blocks {
			if b.Attachment != nil {
				switch {
				case b.Attachment.Kind == "image":
					images++
				case b.Attachment.Kind == "other":
					missing++
				}
			}
		}
	}
	return fmt.Sprintf("rendered %d (%d blocks, %d images, %d attachments without a file), %d locked, %d failed", ok, blocks, images, missing, locked, failed)
}
