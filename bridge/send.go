package main

import (
	"context"
	"errors"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// The message and recipient travel as osascript arguments, never inside the
// script source, so nothing in a message can change what the script does.
const sendScript = `on run argv
	set theText to item 1 of argv
	set theTarget to item 2 of argv
	set theMode to item 3 of argv
	tell application "Messages"
		if theMode is "chat" then
			send theText to chat id theTarget
		else
			send theText to participant theTarget of (1st account whose service type = iMessage)
		end if
	end tell
end run`

const probeScript = `tell application "Messages" to get id of 1st account whose service type = iMessage`

var (
	errAutomationDenied = errors.New("the Mac hasn't allowed imsg-bridge to control Messages (Automation permission)")
	errNoSuchChat       = errors.New("Messages doesn't know that chat or recipient")
)

// Automation tracks whether macOS lets the bridge drive Messages.app.
type Automation struct {
	mu     sync.Mutex
	status string // "ok", "denied", "pending", "unknown"
}

func (a *Automation) Set(s string) {
	a.mu.Lock()
	a.status = s
	a.mu.Unlock()
}

func (a *Automation) Get() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.status == "" {
		return "unknown"
	}
	return a.status
}

func classifyOsaError(out string, err error) error {
	switch {
	case strings.Contains(out, "-1743"), strings.Contains(out, "Not authorized"):
		return errAutomationDenied
	case strings.Contains(out, "-1728"), strings.Contains(out, "Can’t get"), strings.Contains(out, "Can't get"):
		return errNoSuchChat
	}
	if out != "" {
		return errors.New(strings.TrimSpace(out))
	}
	return err
}

// Probe asks Messages for its iMessage account. The first time, macOS shows
// the Automation prompt on the Mac's screen and waits for a click.
func (a *Automation) Probe(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	a.Set("pending")
	out, err := exec.CommandContext(ctx, "osascript", "-e", probeScript).CombinedOutput()
	switch {
	case err == nil:
		a.Set("ok")
	case errors.Is(classifyOsaError(string(out), err), errAutomationDenied):
		a.Set("denied")
	case ctx.Err() != nil:
		a.Set("pending") // the prompt is probably still waiting on the Mac
	default:
		a.Set("unknown")
	}
}

// runSend hands the text to Messages.app. mode is "chat" (target = chat GUID)
// or "handle" (target = phone number or email).
func runSend(ctx context.Context, auto *Automation, text, target, mode string) error {
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, "osascript", "-e", sendScript, text, target, mode).CombinedOutput()
	if err == nil {
		auto.Set("ok")
		return nil
	}
	e := classifyOsaError(string(out), err)
	if errors.Is(e, errAutomationDenied) {
		auto.Set("denied")
	}
	return e
}
