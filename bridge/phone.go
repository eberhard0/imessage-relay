package main

// Phone finder: the Android app posts its position now and then; the Mac keeps the trail and
// serves a small map page (Leaflet, OpenStreetMap tiles) for the owner to look at from any device
// on the tailnet. The page itself needs no token; the data it fetches does (typed once, kept in
// the browser).

import (
	"bufio"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type PhoneFix struct {
	Device     string  `json:"device"`
	Lat        float64 `json:"lat"`
	Lon        float64 `json:"lon"`
	Acc        float64 `json:"acc"`
	Battery    int     `json:"battery"`
	Net        string  `json:"net"`
	At         int64   `json:"at"`
	ReceivedAt int64   `json:"received_at"`
}

type PhoneStore struct {
	mu    sync.Mutex
	path  string
	trail []PhoneFix // oldest first, capped
}

const phoneTrailMax = 5000

func newPhoneStore() *PhoneStore {
	p := &PhoneStore{path: filepath.Join(configDir(), "phone-locations.jsonl")}
	if f, err := os.Open(p.path); err == nil {
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			var fx PhoneFix
			if json.Unmarshal(sc.Bytes(), &fx) == nil {
				p.trail = append(p.trail, fx)
			}
		}
		f.Close()
		if len(p.trail) > phoneTrailMax {
			p.trail = p.trail[len(p.trail)-phoneTrailMax:]
		}
	}
	return p
}

func (p *PhoneStore) add(fx PhoneFix) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.trail = append(p.trail, fx)
	if len(p.trail) > phoneTrailMax {
		p.trail = p.trail[len(p.trail)-phoneTrailMax:]
	}
	if f, err := os.OpenFile(p.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600); err == nil {
		b, _ := json.Marshal(fx)
		f.Write(append(b, '\n'))
		f.Close()
	}
}

// POST /v1/phone/location  {device, lat, lon, acc, battery, net, at}
func (s *Server) phonePost(w http.ResponseWriter, r *http.Request) {
	var fx PhoneFix
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&fx); err != nil || fx.Lat < -90 || fx.Lat > 90 || fx.Lon < -180 || fx.Lon > 180 || (fx.Lat == 0 && fx.Lon == 0) {
		writeErr(w, http.StatusBadRequest, "bad_request", "lat/lon missing")
		return
	}
	fx.ReceivedAt = time.Now().UnixMilli()
	if fx.At == 0 {
		fx.At = fx.ReceivedAt
	}
	if fx.Device == "" {
		fx.Device = "phone"
	}
	s.phone.add(fx)
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "count": len(s.phone.trail)})
}

// GET /v1/phone/location?limit=300 → {"last": …, "trail": [oldest…newest]}
func (s *Server) phoneGet(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > phoneTrailMax {
		limit = 300
	}
	s.phone.mu.Lock()
	t := s.phone.trail
	if len(t) > limit {
		t = t[len(t)-limit:]
	}
	out := make([]PhoneFix, len(t))
	copy(out, t)
	s.phone.mu.Unlock()
	var last any
	if len(out) > 0 {
		last = out[len(out)-1]
	}
	writeJSON(w, http.StatusOK, map[string]any{"last": last, "trail": out})
}

// GET /v1/phone/page — the map page; no token needed for the page, the data call carries it.
func (s *Server) phonePage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write([]byte(phonePageHTML))
}

// publicThenAuth lets the map page through and sends everything else through the token check.
func publicThenAuth(s *Server, mux http.Handler) http.Handler {
	authed := s.auth(mux)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && strings.TrimSuffix(r.URL.Path, "/") == "/v1/phone/page" {
			s.phonePage(w, r)
			return
		}
		authed.ServeHTTP(w, r)
	})
}

const phonePageHTML = `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Where is the Fold</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>html,body,#map{height:100%;margin:0;background:#111;color:#eee;font:14px system-ui}
#bar{position:absolute;top:0;left:0;right:0;z-index:1000;background:rgba(20,20,20,.92);padding:10px 12px;display:flex;gap:10px;align-items:center;flex-wrap:wrap}
#bar input{flex:1;min-width:160px;padding:6px;border-radius:6px;border:1px solid #555;background:#222;color:#eee}
#bar button{padding:6px 12px;border-radius:6px;border:0;background:#3ddc84;color:#000;font-weight:600}
#info{font-size:13px;color:#ccc}</style></head><body>
<div id="bar"><b>Where is the Fold</b><input id="tok" type="password" placeholder="bridge token (kept in this browser)"><button id="go">Show</button><span id="info"></span></div>
<div id="map"></div>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<script>
const tok=document.getElementById('tok'),info=document.getElementById('info');
tok.value=localStorage.getItem('imsg-token')||'';
const map=L.map('map',{zoomControl:true}).setView([-6.2,106.85],11);
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);
let layer=L.layerGroup().addTo(map), first=true;
async function load(){
  const t=tok.value.trim(); if(!t){info.textContent='Enter the token.';return;}
  localStorage.setItem('imsg-token',t);
  let r; try{ r=await fetch('/v1/phone/location?limit=500',{headers:{Authorization:'Bearer '+t}}); }catch(e){info.textContent='Bridge not reachable';return;}
  if(!r.ok){info.textContent='Rejected ('+r.status+'): wrong token?';return;}
  const d=await r.json(); layer.clearLayers();
  if(!d.last){info.textContent='No positions yet.';return;}
  const pts=d.trail.map(p=>[p.lat,p.lon]);
  L.polyline(pts,{color:'#4da3ff',weight:3,opacity:.8}).addTo(layer);
  const l=d.last, when=new Date(l.at);
  L.circle([l.lat,l.lon],{radius:Math.max(l.acc||0,15),color:'#3ddc84',fillOpacity:.15}).addTo(layer);
  L.marker([l.lat,l.lon]).addTo(layer).bindPopup('Last seen '+when.toLocaleString()+'<br>±'+Math.round(l.acc||0)+' m · battery '+l.battery+'% · '+(l.net||'')+'<br><a href="https://www.google.com/maps?q='+l.lat+','+l.lon+'" target="_blank">Open in Google Maps</a>').openPopup();
  info.textContent='Last seen '+when.toLocaleString()+' · battery '+l.battery+'% · '+d.trail.length+' points';
  if(first){map.setView([l.lat,l.lon],15);first=false;}
}
document.getElementById('go').onclick=load; if(tok.value)load(); setInterval(()=>{if(tok.value)load();},60000);
</script></body></html>`
