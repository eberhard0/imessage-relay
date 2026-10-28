package main

import (
	"bytes"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	_ "modernc.org/sqlite"
)

// Apple's Messages database stores dates as nanoseconds since 2001-01-01
// (very old rows: seconds). This is that epoch in Unix seconds.
const appleEpoch = 978307200

func appleToUnixMs(d int64) int64 {
	if d == 0 {
		return 0
	}
	if d > 100_000_000_000 {
		return d/1_000_000 + appleEpoch*1000
	}
	return (d + appleEpoch) * 1000
}

func unixToApple(t time.Time) int64 {
	return (t.Unix() - appleEpoch) * 1_000_000_000
}

// Chat styles in chat.style.
const styleGroup = 43

type Attachment struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
	Mime string `json:"mime"`
	Size int64  `json:"size"`
}

type Message struct {
	ID          int64        `json:"id"`
	GUID        string       `json:"guid"`
	ChatGUID    string       `json:"chat_guid"`
	ChatName    string       `json:"chat_name,omitempty"`
	IsGroup     bool         `json:"is_group"`
	Date        int64        `json:"date"` // Unix milliseconds
	FromMe      bool         `json:"from_me"`
	Sender      string       `json:"sender,omitempty"` // handle (phone or email); empty when from me
	SenderName  string       `json:"sender_name,omitempty"`
	Text        string       `json:"text"`
	Error       int          `json:"error,omitempty"`
	Attachments []Attachment `json:"attachments,omitempty"`
}

type Participant struct {
	Handle string `json:"handle"`
	Name   string `json:"name,omitempty"`
}

type Chat struct {
	GUID         string        `json:"guid"`
	Name         string        `json:"name"`
	IsGroup      bool          `json:"is_group"`
	Participants []Participant `json:"participants"`
	LastMessage  *Message      `json:"last_message,omitempty"`
	Unread       int           `json:"unread"`
}

// Store reads chat.db. Every call opens a fresh read-only connection:
// Messages.app writes constantly through the WAL, and a long-lived reader
// would pin an old snapshot.
type Store struct {
	path     string
	contacts *Contacts
}

func (s *Store) open() (*sql.DB, error) {
	dsn := "file:" + (&url.URL{Path: s.path}).EscapedPath() + "?mode=ro&_pragma=busy_timeout(3000)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	return db, nil
}

// MaxRowID is also the readability check behind /v1/health.
func (s *Store) MaxRowID() (int64, error) {
	db, err := s.open()
	if err != nil {
		return 0, err
	}
	defer db.Close()
	var id sql.NullInt64
	if err := db.QueryRow(`SELECT MAX(ROWID) FROM message`).Scan(&id); err != nil {
		return 0, explainOpenError(err)
	}
	return id.Int64, nil
}

func explainOpenError(err error) error {
	msg := err.Error()
	if strings.Contains(msg, "authorization denied") || strings.Contains(msg, "unable to open") ||
		strings.Contains(msg, "operation not permitted") {
		return fmt.Errorf("can't read chat.db — give imsg-bridge Full Disk Access (%v)", err)
	}
	return err
}

// Normal messages only: no reactions (associated_message_type 2000–3006) and
// no group events (item_type ≠ 0).
const messageSelect = `
SELECT m.ROWID, COALESCE(m.guid,''), m.date, m.is_from_me, m.text, m.attributedBody, m.error,
       COALESCE(h.id,''), c.guid, COALESCE(c.display_name,''), c.style, m.cache_has_attachments
FROM message m
JOIN chat_message_join j ON j.message_id = m.ROWID
JOIN chat c ON c.ROWID = j.chat_id
LEFT JOIN handle h ON h.ROWID = m.handle_id
WHERE m.associated_message_type = 0 AND m.item_type = 0`

// scanMessages reads rows of messageSelect, keeping the first chat for a
// message that joins several chat rows.
func (s *Store) scanMessages(db *sql.DB, rows *sql.Rows) ([]Message, error) {
	defer rows.Close()
	var out []Message
	seen := map[int64]bool{}
	var withAttachments []int
	for rows.Next() {
		var (
			m                   Message
			date                int64
			fromMe, hasAttach   int
			style               int
			text                sql.NullString
			body                []byte
			chatName            string
		)
		if err := rows.Scan(&m.ID, &m.GUID, &date, &fromMe, &text, &body, &m.Error,
			&m.Sender, &m.ChatGUID, &chatName, &style, &hasAttach); err != nil {
			return nil, err
		}
		if seen[m.ID] {
			continue
		}
		seen[m.ID] = true
		m.Date = appleToUnixMs(date)
		m.FromMe = fromMe == 1
		m.IsGroup = style == styleGroup
		m.ChatName = chatName
		m.Text = text.String
		if m.Text == "" {
			m.Text = textFromAttributedBody(body)
		}
		// U+FFFC marks where an attachment sat in the text.
		m.Text = strings.TrimSpace(strings.ReplaceAll(m.Text, "￼", ""))
		if m.FromMe {
			m.Sender = ""
		} else {
			m.SenderName = s.contacts.Name(m.Sender)
		}
		if hasAttach == 1 {
			withAttachments = append(withAttachments, len(out))
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, i := range withAttachments {
		atts, err := attachmentsFor(db, out[i].ID)
		if err != nil {
			return nil, err
		}
		out[i].Attachments = atts
	}
	return out, nil
}

func attachmentsFor(db *sql.DB, messageID int64) ([]Attachment, error) {
	rows, err := db.Query(`
SELECT a.ROWID, COALESCE(a.transfer_name,''), COALESCE(a.mime_type,''), COALESCE(a.total_bytes,0)
FROM attachment a JOIN message_attachment_join maj ON maj.attachment_id = a.ROWID
WHERE maj.message_id = ?`, messageID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Attachment
	for rows.Next() {
		var a Attachment
		if err := rows.Scan(&a.ID, &a.Name, &a.Mime, &a.Size); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// NewSince returns messages, both directions, with ROWID above [after] and a
// date after [notBefore]. The date filter is the iCloud-backfill guard: a
// sync hands years-old messages brand-new ROWIDs.
func (s *Store) NewSince(after int64, notBefore time.Time, limit int) ([]Message, error) {
	db, err := s.open()
	if err != nil {
		return nil, err
	}
	defer db.Close()
	rows, err := db.Query(messageSelect+` AND m.ROWID > ? AND m.date > ? ORDER BY m.ROWID LIMIT ?`,
		after, unixToApple(notBefore), limit)
	if err != nil {
		return nil, explainOpenError(err)
	}
	return s.scanMessages(db, rows)
}

// History returns up to [limit] messages of one chat, oldest first, older than
// the message [before] (0: the newest).
func (s *Store) History(chatGUID string, before int64, limit int) ([]Message, error) {
	db, err := s.open()
	if err != nil {
		return nil, err
	}
	defer db.Close()
	q := messageSelect + ` AND c.guid = ?`
	args := []any{chatGUID}
	if before > 0 {
		// Paged by ROWID as the cursor, ordered by date: backfilled rows have
		// new ROWIDs but old dates.
		q += ` AND m.date < (SELECT date FROM message WHERE ROWID = ?)`
		args = append(args, before)
	}
	q += ` ORDER BY m.date DESC LIMIT ?`
	args = append(args, limit)
	rows, err := db.Query(q, args...)
	if err != nil {
		return nil, explainOpenError(err)
	}
	msgs, err := s.scanMessages(db, rows)
	if err != nil {
		return nil, err
	}
	for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
		msgs[i], msgs[j] = msgs[j], msgs[i]
	}
	return msgs, nil
}

// Chats returns the most recently active conversations.
func (s *Store) Chats(limit int) ([]Chat, error) {
	db, err := s.open()
	if err != nil {
		return nil, err
	}
	defer db.Close()
	rows, err := db.Query(`
SELECT j.chat_id, MAX(j.message_date) AS last FROM chat_message_join j
GROUP BY j.chat_id ORDER BY last DESC LIMIT ?`, limit)
	if err != nil {
		return nil, explainOpenError(err)
	}
	var ids []int64
	for rows.Next() {
		var id int64
		var last sql.NullInt64
		if err := rows.Scan(&id, &last); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()

	unreadCutoff := unixToApple(time.Now().Add(-30 * 24 * time.Hour))
	var out []Chat
	for _, id := range ids {
		var c Chat
		var style int
		var identifier string
		if err := db.QueryRow(`SELECT guid, COALESCE(display_name,''), style, COALESCE(chat_identifier,'') FROM chat WHERE ROWID = ?`, id).
			Scan(&c.GUID, &c.Name, &style, &identifier); err != nil {
			continue
		}
		c.IsGroup = style == styleGroup

		prow, err := db.Query(`SELECT h.id FROM chat_handle_join chj JOIN handle h ON h.ROWID = chj.handle_id WHERE chj.chat_id = ?`, id)
		if err == nil {
			for prow.Next() {
				var p Participant
				if prow.Scan(&p.Handle) == nil {
					p.Name = s.contacts.Name(p.Handle)
					c.Participants = append(c.Participants, p)
				}
			}
			prow.Close()
		}
		if c.Name == "" {
			c.Name = s.chatTitle(c.Participants, identifier)
		}

		lrows, err := db.Query(messageSelect+` AND j.chat_id = ? ORDER BY m.date DESC LIMIT 1`, id)
		if err == nil {
			if msgs, err := s.scanMessages(db, lrows); err == nil && len(msgs) > 0 {
				c.LastMessage = &msgs[0]
			}
		}
		_ = db.QueryRow(`
SELECT COUNT(*) FROM message m JOIN chat_message_join j ON j.message_id = m.ROWID
WHERE j.chat_id = ? AND m.is_from_me = 0 AND m.is_read = 0
  AND m.associated_message_type = 0 AND m.item_type = 0 AND m.date > ?`, id, unreadCutoff).Scan(&c.Unread)
		out = append(out, c)
	}
	return out, nil
}

func (s *Store) chatTitle(ps []Participant, identifier string) string {
	var names []string
	for _, p := range ps {
		if p.Name != "" {
			names = append(names, p.Name)
		} else {
			names = append(names, p.Handle)
		}
	}
	if len(names) == 0 {
		return identifier
	}
	return strings.Join(names, ", ")
}

// ChatForHandle finds the one-to-one chat with [handle], for confirming a
// send addressed by handle.
func (s *Store) ChatForHandle(handle string) (string, error) {
	db, err := s.open()
	if err != nil {
		return "", err
	}
	defer db.Close()
	var guid string
	err = db.QueryRow(`SELECT guid FROM chat WHERE chat_identifier = ? AND style != ? ORDER BY ROWID DESC LIMIT 1`,
		handle, styleGroup).Scan(&guid)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return guid, err
}

// LatestFromMe is the newest sent message in a chat at or after [since], used
// to confirm a send went out (error 0) or failed.
func (s *Store) LatestFromMe(chatGUID string, since time.Time) (*Message, error) {
	db, err := s.open()
	if err != nil {
		return nil, err
	}
	defer db.Close()
	rows, err := db.Query(messageSelect+` AND c.guid = ? AND m.is_from_me = 1 AND m.date >= ? ORDER BY m.date DESC LIMIT 1`,
		chatGUID, unixToApple(since))
	if err != nil {
		return nil, err
	}
	msgs, err := s.scanMessages(db, rows)
	if err != nil || len(msgs) == 0 {
		return nil, err
	}
	return &msgs[0], nil
}

// AttachmentFile returns the on-disk path and type of an attachment.
func (s *Store) AttachmentFile(id int64) (path, mime, name string, err error) {
	db, err := s.open()
	if err != nil {
		return "", "", "", err
	}
	defer db.Close()
	err = db.QueryRow(`SELECT COALESCE(filename,''), COALESCE(mime_type,''), COALESCE(transfer_name,'') FROM attachment WHERE ROWID = ?`, id).
		Scan(&path, &mime, &name)
	return path, mime, name, err
}

// textFromAttributedBody pulls the plain string out of an NSAttributedString
// typedstream: after "NSString" comes a "+" marker and a length — one byte,
// or 0x81 then 2 bytes little-endian, or 0x82 then 3 bytes.
func textFromAttributedBody(blob []byte) string {
	i := bytes.Index(blob, []byte("NSString"))
	if i < 0 {
		return ""
	}
	rest := blob[i+len("NSString"):]
	p := bytes.IndexByte(rest, '+')
	if p < 0 || p+1 >= len(rest) {
		return ""
	}
	rest = rest[p+1:]
	var n, start int
	switch rest[0] {
	case 0x81:
		if len(rest) < 3 {
			return ""
		}
		n, start = int(rest[1])|int(rest[2])<<8, 3
	case 0x82:
		if len(rest) < 4 {
			return ""
		}
		n, start = int(rest[1])|int(rest[2])<<8|int(rest[3])<<16, 4
	default:
		n, start = int(rest[0]), 1
	}
	if start+n > len(rest) {
		n = len(rest) - start
	}
	text := rest[start : start+n]
	if !utf8.Valid(text) {
		return strings.ToValidUTF8(string(text), "")
	}
	return string(text)
}
