package main

import (
	"database/sql"
	"net/url"
	"path/filepath"
	"strings"
	"sync"
)

// Contacts maps phone numbers and emails to names from the Mac's Contacts
// databases (best effort, read-only; covered by the same Full Disk Access).
type Contacts struct {
	mu    sync.RWMutex
	names map[string]string
}

func phoneKey(s string) string {
	var d []byte
	for i := 0; i < len(s); i++ {
		if s[i] >= '0' && s[i] <= '9' {
			d = append(d, s[i])
		}
	}
	// The last nine digits match +62 812… and 0812… alike.
	if len(d) < 6 {
		return ""
	}
	if len(d) > 9 {
		d = d[len(d)-9:]
	}
	return "p:" + string(d)
}

func handleKey(handle string) string {
	if strings.Contains(handle, "@") {
		return "e:" + strings.ToLower(strings.TrimSpace(handle))
	}
	return phoneKey(handle)
}

// Name returns the contact name for a handle, or "".
func (c *Contacts) Name(handle string) string {
	if c == nil || handle == "" {
		return ""
	}
	key := handleKey(handle)
	if key == "" {
		return ""
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.names[key]
}

func (c *Contacts) Count() int {
	if c == nil {
		return 0
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.names)
}

// Reload rereads every AddressBook database; errors leave the old map in place.
func (c *Contacts) Reload(home string) {
	base := filepath.Join(home, "Library", "Application Support", "AddressBook")
	files, _ := filepath.Glob(filepath.Join(base, "Sources", "*", "AddressBook-v22.abcddb"))
	files = append(files, filepath.Join(base, "AddressBook-v22.abcddb"))

	names := map[string]string{}
	for _, f := range files {
		readAddressBook(f, names)
	}
	if len(names) == 0 {
		return
	}
	c.mu.Lock()
	c.names = names
	c.mu.Unlock()
}

func readAddressBook(path string, into map[string]string) {
	db, err := sql.Open("sqlite", "file:"+(&url.URL{Path: path}).EscapedPath()+"?mode=ro")
	if err != nil {
		return
	}
	defer db.Close()

	people := map[int64]string{}
	rows, err := db.Query(`SELECT Z_PK, COALESCE(ZFIRSTNAME,''), COALESCE(ZLASTNAME,''), COALESCE(ZORGANIZATION,'') FROM ZABCDRECORD`)
	if err != nil {
		return
	}
	for rows.Next() {
		var id int64
		var first, last, org string
		if rows.Scan(&id, &first, &last, &org) != nil {
			continue
		}
		name := strings.TrimSpace(first + " " + last)
		if name == "" {
			name = org
		}
		if name != "" {
			people[id] = name
		}
	}
	rows.Close()

	add := func(query string, key func(string) string) {
		rows, err := db.Query(query)
		if err != nil {
			return
		}
		defer rows.Close()
		for rows.Next() {
			var owner sql.NullInt64
			var value sql.NullString
			if rows.Scan(&owner, &value) != nil || !owner.Valid {
				continue
			}
			if name, ok := people[owner.Int64]; ok {
				if k := key(value.String); k != "" {
					into[k] = name
				}
			}
		}
	}
	add(`SELECT ZOWNER, ZFULLNUMBER FROM ZABCDPHONENUMBER`, phoneKey)
	add(`SELECT ZOWNER, ZADDRESS FROM ZABCDEMAILADDRESS`, func(s string) string {
		if s == "" {
			return ""
		}
		return "e:" + strings.ToLower(strings.TrimSpace(s))
	})
}
