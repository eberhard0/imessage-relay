package main

// The Photos library, read-only: albums, their items, and the files Photos.app keeps on disk
// (thumbnails, full-size previews, originals when they are local). Photos.sqlite is a Core Data
// store; the album↔asset join table's name changes with the Photos version, so it is looked up.

import (
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	albumKindUser   = 2
	albumKindShared = 1505
	albumKindFolder = 4000
	albumKindRoot   = 3999
)

type LibraryStore struct {
	dir string // ~/Pictures/Photos Library.photoslibrary

	mu        sync.Mutex
	joinTable string
	joinAlbum string
	joinAsset string
}

func newLibraryStore() *LibraryStore {
	home, _ := os.UserHomeDir()
	return &LibraryStore{dir: filepath.Join(home, "Pictures", "Photos Library.photoslibrary")}
}

func (l *LibraryStore) open() (*sql.DB, error) {
	path := filepath.Join(l.dir, "database", "Photos.sqlite")
	if _, err := os.Stat(path); err != nil {
		return nil, errors.New("no Photos library at " + l.dir)
	}
	dsn := "file:" + (&url.URL{Path: path}).EscapedPath() + "?mode=ro&_pragma=busy_timeout(3000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	return db, nil
}

// join finds the Z_<n>ASSETS table that links albums to assets (Z_34ASSETS on macOS 27).
func (l *LibraryStore) join(db *sql.DB) (table, albumCol, assetCol string, err error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.joinTable != "" {
		return l.joinTable, l.joinAlbum, l.joinAsset, nil
	}
	rows, err := db.Query(`SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'Z\_%ASSETS' ESCAPE '\'`)
	if err != nil {
		return "", "", "", err
	}
	var names []string
	for rows.Next() {
		var n string
		if rows.Scan(&n) == nil {
			names = append(names, n)
		}
	}
	rows.Close()
	for _, n := range names {
		cols, err := db.Query("PRAGMA table_info(" + n + ")")
		if err != nil {
			continue
		}
		var a, b string
		for cols.Next() {
			var cid int
			var name, ctype string
			var notnull int
			var dflt sql.NullString
			var pk int
			if cols.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk) != nil {
				continue
			}
			switch {
			case strings.HasSuffix(name, "ALBUMS"):
				a = name
			case strings.HasSuffix(name, "ASSETS") && !strings.HasPrefix(name, "Z_FOK"):
				b = name
			}
		}
		cols.Close()
		if a != "" && b != "" {
			l.joinTable, l.joinAlbum, l.joinAsset = n, a, b
			return n, a, b, nil
		}
	}
	return "", "", "", errors.New("album join table not found in Photos.sqlite")
}

type Album struct {
	ID    string `json:"id"`
	Title string `json:"title"`
	Kind  string `json:"kind"` // smart | album | shared
	Path  string `json:"path,omitempty"`
	Count int    `json:"count"`
	Cover string `json:"cover,omitempty"`
	Owner string `json:"owner,omitempty"`
}

type MediaItem struct {
	UUID     string  `json:"uuid"`
	Name     string  `json:"name"`
	Video    bool    `json:"video"`
	Date     int64   `json:"date"`
	Width    int     `json:"w"`
	Height   int     `json:"h"`
	Duration float64 `json:"dur,omitempty"`
	Favorite bool    `json:"fav"`
}

const assetCols = `ZUUID, COALESCE(ZFILENAME,''), ZKIND, ZDATECREATED, COALESCE(ZWIDTH,0), COALESCE(ZHEIGHT,0), COALESCE(ZDURATION,0), ZFAVORITE`

func scanItem(rows *sql.Rows) (MediaItem, error) {
	var it MediaItem
	var kind int
	var date sql.NullFloat64
	var fav int
	err := rows.Scan(&it.UUID, &it.Name, &kind, &date, &it.Width, &it.Height, &it.Duration, &fav)
	it.Video = kind == 1
	it.Date = notesToUnixMs(date)
	it.Favorite = fav == 1
	if !it.Video {
		it.Duration = 0
	}
	return it, err
}

// albums: the smart ones first, then the user's albums (with their folder path), then shared albums.
func (l *LibraryStore) albums() ([]Album, error) {
	db, err := l.open()
	if err != nil {
		return nil, err
	}
	defer db.Close()
	out := []Album{}
	count := func(where string) int {
		var n int
		_ = db.QueryRow(`SELECT COUNT(*) FROM ZASSET WHERE ZTRASHEDSTATE=0 AND ZHIDDEN=0 ` + where).Scan(&n)
		return n
	}
	cover := func(where string) string {
		var u string
		_ = db.QueryRow(`SELECT ZUUID FROM ZASSET WHERE ZTRASHEDSTATE=0 AND ZHIDDEN=0 ` + where + ` ORDER BY ZDATECREATED DESC LIMIT 1`).Scan(&u)
		return u
	}
	out = append(out,
		Album{ID: "recents", Title: "Recents", Kind: "smart", Count: count(""), Cover: cover("")},
		Album{ID: "favorites", Title: "Favourites", Kind: "smart", Count: count("AND ZFAVORITE=1"), Cover: cover("AND ZFAVORITE=1")},
		Album{ID: "videos", Title: "Videos", Kind: "smart", Count: count("AND ZKIND=1"), Cover: cover("AND ZKIND=1")},
	)

	// Folder names, to show "Folder › Album".
	folders := map[int64]struct {
		title  string
		parent sql.NullInt64
	}{}
	frows, err := db.Query(`SELECT Z_PK, COALESCE(ZTITLE,''), ZPARENTFOLDER FROM ZGENERICALBUM WHERE ZKIND=? AND ZTRASHEDSTATE=0`, albumKindFolder)
	if err == nil {
		for frows.Next() {
			var pk int64
			var f struct {
				title  string
				parent sql.NullInt64
			}
			if frows.Scan(&pk, &f.title, &f.parent) == nil {
				folders[pk] = f
			}
		}
		frows.Close()
	}
	pathOf := func(parent sql.NullInt64) string {
		var parts []string
		for parent.Valid {
			f, ok := folders[parent.Int64]
			if !ok || f.title == "" {
				break
			}
			parts = append([]string{f.title}, parts...)
			parent = f.parent
		}
		return strings.Join(parts, " › ")
	}

	table, albumCol, assetCol, err := l.join(db)
	if err != nil {
		return nil, err
	}
	q := `SELECT a.Z_PK, COALESCE(a.ZTITLE,''), a.ZKIND, a.ZPARENTFOLDER, COALESCE(a.ZCLOUDOWNERFULLNAME,''),
	        (SELECT COUNT(*) FROM ` + table + ` j JOIN ZASSET s ON s.Z_PK=j.` + assetCol + ` WHERE j.` + albumCol + `=a.Z_PK AND s.ZTRASHEDSTATE=0 AND s.ZHIDDEN=0),
	        (SELECT s.ZUUID FROM ` + table + ` j JOIN ZASSET s ON s.Z_PK=j.` + assetCol + ` WHERE j.` + albumCol + `=a.Z_PK AND s.ZTRASHEDSTATE=0 AND s.ZHIDDEN=0 ORDER BY s.ZDATECREATED DESC LIMIT 1)
	      FROM ZGENERICALBUM a WHERE a.ZKIND IN (?, ?) AND a.ZTRASHEDSTATE=0 ORDER BY a.ZKIND, a.ZTITLE COLLATE NOCASE`
	rows, err := db.Query(q, albumKindUser, albumKindShared)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var pk int64
		var title, owner string
		var kind int
		var parent sql.NullInt64
		var n int
		var cov sql.NullString
		if err := rows.Scan(&pk, &title, &kind, &parent, &owner, &n, &cov); err != nil {
			continue
		}
		if title == "" {
			continue
		}
		al := Album{ID: strconv.FormatInt(pk, 10), Title: title, Kind: "album", Count: n, Cover: cov.String}
		if kind == albumKindShared {
			al.Kind = "shared"
			al.Owner = owner
		} else {
			al.Path = pathOf(parent)
		}
		out = append(out, al)
	}
	return out, nil
}

// items of one album, newest first, paged.
func (l *LibraryStore) items(album string, offset, limit int) ([]MediaItem, int, error) {
	db, err := l.open()
	if err != nil {
		return nil, 0, err
	}
	defer db.Close()
	base := `FROM ZASSET s WHERE s.ZTRASHEDSTATE=0 AND s.ZHIDDEN=0`
	var args []any
	switch album {
	case "recents":
	case "favorites":
		base += ` AND s.ZFAVORITE=1`
	case "videos":
		base += ` AND s.ZKIND=1`
	default:
		pk, err := strconv.ParseInt(album, 10, 64)
		if err != nil {
			return nil, 0, errors.New("unknown album")
		}
		table, albumCol, assetCol, err := l.join(db)
		if err != nil {
			return nil, 0, err
		}
		base += ` AND s.Z_PK IN (SELECT ` + assetCol + ` FROM ` + table + ` WHERE ` + albumCol + `=?)`
		args = append(args, pk)
	}
	var total int
	if err := db.QueryRow(`SELECT COUNT(*) `+base, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.Query(`SELECT `+assetCols+` `+base+` ORDER BY s.ZDATECREATED DESC LIMIT ? OFFSET ?`, append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := []MediaItem{}
	for rows.Next() {
		it, err := scanItem(rows)
		if err == nil {
			out = append(out, it)
		}
	}
	return out, total, nil
}

// file locates what Photos.app has on disk for one asset.
//   thumb    → the small derivative (…_1_105_c / masters …_4_5005_c)
//   preview  → the full-size preview JPEG (…_1_102_o / _1_102_a), the original if there is none
//   original → the original file; "" when it only lives in iCloud
func (l *LibraryStore) file(uuid, size string) (path, name string, original bool, err error) {
	if len(uuid) < 2 || strings.ContainsAny(uuid, "/\\.") {
		return "", "", false, errors.New("bad uuid")
	}
	db, err := l.open()
	if err != nil {
		return "", "", false, err
	}
	var dir, filename string
	err = db.QueryRow(`SELECT COALESCE(ZDIRECTORY,''), COALESCE(ZFILENAME,'') FROM ZASSET WHERE ZUUID=?`, uuid).Scan(&dir, &filename)
	db.Close()
	if err != nil {
		return "", "", false, errors.New("not in the library")
	}
	first := func(patterns ...string) string {
		for _, p := range patterns {
			if m, _ := filepath.Glob(p); len(m) > 0 {
				return m[0]
			}
		}
		return ""
	}
	x := uuid[:1]
	// Shared-album files sit under scopes/cloudsharing with the same layout.
	roots := []string{l.dir}
	if strings.HasPrefix(dir, "scopes/") {
		parts := strings.SplitN(dir, "/", 3)
		if len(parts) >= 2 {
			roots = append([]string{filepath.Join(l.dir, parts[0], parts[1])}, roots...)
		}
	}
	var thumb, preview, orig string
	for _, r := range roots {
		d := filepath.Join(r, "resources", "derivatives")
		if thumb == "" {
			thumb = first(filepath.Join(d, x, uuid+"_1_105_c.*"), filepath.Join(d, "masters", x, uuid+"_4_5005_c.*"), filepath.Join(d, x, uuid+"_1_102_*"))
		}
		if preview == "" {
			preview = first(filepath.Join(d, x, uuid+"_1_102_o.*"), filepath.Join(d, x, uuid+"_1_102_a.*"), filepath.Join(d, x, uuid+"_1_102_*"))
		}
	}
	if filename != "" {
		cand := filepath.Join(l.dir, dir, filename)
		if !strings.HasPrefix(dir, "scopes/") {
			cand = filepath.Join(l.dir, "originals", dir, filename)
		}
		if st, err := os.Stat(cand); err == nil && st.Size() > 0 {
			orig = cand
		}
	}
	switch size {
	case "thumb":
		if thumb != "" {
			return thumb, filepath.Base(thumb), false, nil
		}
		if preview != "" {
			return preview, filepath.Base(preview), false, nil
		}
	case "original":
		if orig != "" {
			return orig, filename, true, nil
		}
		if preview != "" {
			return preview, strings.TrimSuffix(filename, filepath.Ext(filename)) + ".jpg", false, nil
		}
	default: // preview
		if preview != "" {
			return preview, strings.TrimSuffix(filename, filepath.Ext(filename)) + ".jpg", false, nil
		}
		if orig != "" {
			return orig, filename, true, nil
		}
	}
	return "", "", false, errors.New("not on the Mac (only in iCloud)")
}

// GET /v1/library/albums
func (s *Server) libraryAlbums(w http.ResponseWriter, r *http.Request) {
	albums, err := s.library.albums()
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "library", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"albums": albums, "generated": time.Now().UnixMilli()})
}

// GET /v1/library/items?album=recents|favorites|videos|<id>&offset=0&limit=200
func (s *Server) libraryItems(w http.ResponseWriter, r *http.Request) {
	album := r.URL.Query().Get("album")
	if album == "" {
		album = "recents"
	}
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	items, total, err := s.library.items(album, offset, limit)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "library", err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "total": total, "offset": offset})
}

// GET /v1/library/media/{uuid}?size=thumb|preview|original  (range requests work, for video)
func (s *Server) libraryMedia(w http.ResponseWriter, r *http.Request) {
	uuid := strings.ToUpper(r.PathValue("uuid"))
	size := r.URL.Query().Get("size")
	path, name, original, err := s.library.file(uuid, size)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_local", err.Error())
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, http.StatusNotFound, "not_local", "the file isn't readable on the Mac")
		return
	}
	defer f.Close()
	st, _ := f.Stat()
	if ct := mimeByExt(path); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	w.Header().Set("X-Original", strconv.FormatBool(original))
	w.Header().Set("Content-Disposition", "inline; filename="+strconv.Quote(name))
	w.Header().Set("Cache-Control", "private, max-age=86400")
	http.ServeContent(w, r, name, st.ModTime(), f)
}

func mimeByExt(path string) string {
	switch strings.ToLower(filepath.Ext(path)) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".heic":
		return "image/heic"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".mp4":
		return "video/mp4"
	case ".mov":
		return "video/quicktime"
	case ".m4v":
		return "video/x-m4v"
	}
	return ""
}

var _ = json.Marshal
