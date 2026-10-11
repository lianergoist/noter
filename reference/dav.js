/* dav.js - lille CalDAV-klient til VJOURNAL-noter (window.Dav i browseren, require() i Node).
 *
 * I browseren kører siden på samme domæne som Radicale, så der er ingen CORS, og
 * browserens egen Basic-login-dialog tager sig af adgangskoden: koden her ser den aldrig.
 * I Node (tests) angives origin og et færdigt "Basic ..."-headerlinje i options. */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory(require("./ical.js"));
  else root.Dav = factory(root.Ical);
})(typeof self !== "undefined" ? self : this, function (Ical) {
  "use strict";

  var DAV = "DAV:";
  var CAL = "urn:ietf:params:xml:ns:caldav";
  var CS = "http://calendarserver.org/ns/";
  var NSDECL = 'xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://calendarserver.org/ns/"';
  var XML_HEAD = '<?xml version="1.0" encoding="utf-8"?>';
  var XML_CT = { "Content-Type": "application/xml; charset=utf-8" };

  class DavError extends Error {
    constructor(status, msg) {
      super(status ? "HTTP " + status + (msg ? ": " + msg : "") : msg);
      this.name = "DavError";
      this.status = status;
    }
  }
  class AuthError extends DavError {
    constructor(msg) { super(401, msg || "login mislykkedes"); this.name = "AuthError"; }
  }
  class NetworkError extends Error {
    constructor(msg) { super(msg); this.name = "NetworkError"; }
  }

  // Hrefs gemmes altid som afkodede stier, så sammenligning er entydig.
  function normHref(href) {
    var path;
    try { path = new URL(href, "http://x").pathname; } catch (e) { path = href; }
    try { return decodeURIComponent(path); } catch (e) { return path; }
  }
  function encodePath(p) { return p.split("/").map(encodeURIComponent).join("/"); }
  function withSlash(p) { return p.charAt(p.length - 1) === "/" ? p : p + "/"; }

  // -- XML-hjælpere (kun direkte børn, så indlejrede <href> ikke forveksles) ----
  function kids(el, ns, name) {
    var out = [];
    for (var n = el.firstChild; n; n = n.nextSibling)
      if (n.nodeType === 1 && n.namespaceURI === ns && n.localName === name) out.push(n);
    return out;
  }
  function kid(el, ns, name) { return kids(el, ns, name)[0] || null; }
  function txt(el) { return el ? el.textContent : null; }

  function okProps(resp) {
    var out = [];
    kids(resp, DAV, "propstat").forEach(function (ps) {
      if (/\s200\b/.test(txt(kid(ps, DAV, "status")) || "")) {
        var p = kid(ps, DAV, "prop");
        if (p) out.push(p);
      }
    });
    return out;
  }
  function findProp(props, ns, name) {
    for (var i = 0; i < props.length; i++) {
      var e = kid(props[i], ns, name);
      if (e) return e;
    }
    return null;
  }

  function toNote(href, etag, raw) {
    var p = Ical.parseIcs(raw);
    return { uid: p.uid, href: href, etag: etag, raw: raw, title: p.title, body: p.body, tags: p.tags, modified: p.modified };
  }

  class DavClient {
    constructor(opts) {
      opts = opts || {};
      this.origin = opts.origin || "";   // "" = samme origin som siden
      this.auth = opts.auth || null;     // kun til Node/tests
      this._fetch = opts.fetch || function (u, i) { return fetch(u, i); };
      this._parser = opts.parser || null;
    }

    get parser() {
      if (!this._parser) this._parser = new DOMParser();
      return this._parser;
    }

    async request(method, path, options) {
      options = options || {};
      var headers = Object.assign({}, options.headers);
      if (this.auth) headers["Authorization"] = this.auth;
      var res;
      try {
        res = await this._fetch(this.origin + encodePath(path), {
          method: method, body: options.body, headers: headers, cache: "no-store", credentials: "same-origin",
        });
      } catch (e) {
        throw new NetworkError((e && e.message) || String(e));
      }
      var text = await res.text();
      if (res.status === 401) throw new AuthError();
      return { status: res.status, headers: res.headers, text: text };
    }

    parseXml(text) {
      var doc = this.parser.parseFromString(text, "application/xml");
      if (doc.getElementsByTagName("parsererror").length) throw new DavError(0, "ugyldigt XML-svar fra serveren");
      return doc;
    }

    async propfind(path, depth, inner) {
      var body = XML_HEAD + "<D:propfind " + NSDECL + ">" + inner + "</D:propfind>";
      var r = await this.request("PROPFIND", path, { body: body, headers: Object.assign({ Depth: String(depth) }, XML_CT) });
      if (r.status !== 207) throw new DavError(r.status, "PROPFIND " + path);
      return this.parseXml(r.text);
    }

    // Find brugerens samlinger. Returnerer [{href, name, comps, journal, onlyJournal}].
    async discover() {
      var doc = await this.propfind("/", 0, "<D:prop><D:current-user-principal/></D:prop>");
      var cup = doc.getElementsByTagNameNS(DAV, "current-user-principal")[0];
      var href = cup && cup.getElementsByTagNameNS(DAV, "href")[0];
      if (!href) throw new DavError(0, "kunne ikke finde din bruger på serveren");
      var principal = withSlash(normHref(href.textContent.trim()));

      doc = await this.propfind(principal, 1,
        "<D:prop><D:displayname/><D:resourcetype/><C:supported-calendar-component-set/></D:prop>");
      var out = [];
      kids(doc.documentElement, DAV, "response").forEach(function (resp) {
        var path = withSlash(normHref(txt(kid(resp, DAV, "href")).trim()));
        if (path === principal) return;
        var props = okProps(resp);
        var rt = findProp(props, DAV, "resourcetype");
        if (!rt || !kids(rt, CAL, "calendar").length) return;
        var setEl = findProp(props, CAL, "supported-calendar-component-set");
        var comps = setEl ? kids(setEl, CAL, "comp").map(function (c) { return (c.getAttribute("name") || "").toUpperCase(); }) : [];
        var dn = txt(findProp(props, DAV, "displayname"));
        out.push({
          href: path,
          name: dn || path.split("/").filter(Boolean).pop() || path,
          comps: comps,
          journal: comps.indexOf("VJOURNAL") >= 0,
          onlyJournal: comps.length === 1 && comps[0] === "VJOURNAL",
        });
      });
      return out;
    }

    // Billig ændringstjek: samme ctag = intet nyt. null hvis serveren ikke har den.
    async getCtag(col) {
      var body = XML_HEAD + "<D:propfind " + NSDECL + "><D:prop><CS:getctag/></D:prop></D:propfind>";
      var r = await this.request("PROPFIND", col, { body: body, headers: Object.assign({ Depth: "0" }, XML_CT) });
      if (r.status !== 207) return null;
      var el = this.parseXml(r.text).getElementsByTagNameNS(CS, "getctag")[0];
      return el ? el.textContent : null;
    }

    async getEtag(href) {
      var doc = await this.propfind(href, 0, "<D:prop><D:getetag/></D:prop>");
      var el = doc.getElementsByTagNameNS(DAV, "getetag")[0];
      if (!el) throw new DavError(0, "svar uden etag");
      return el.textContent;
    }

    // Alle VJOURNAL-noter i samlingen. Objekter af anden type ignoreres.
    async listNotes(col) {
      var body = XML_HEAD + "<C:calendar-query " + NSDECL + "><D:prop><D:getetag/><C:calendar-data/></D:prop>" +
        '<C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VJOURNAL"/></C:comp-filter></C:filter></C:calendar-query>';
      var r = await this.request("REPORT", col, { body: body, headers: Object.assign({ Depth: "1" }, XML_CT) });
      if (r.status !== 207) throw new DavError(r.status, "REPORT " + col);
      var notes = [];
      kids(this.parseXml(r.text).documentElement, DAV, "response").forEach(function (resp) {
        var props = okProps(resp);
        var etag = txt(findProp(props, DAV, "getetag"));
        var data = txt(findProp(props, CAL, "calendar-data"));
        if (!etag || !data) return;
        try { notes.push(toNote(normHref(txt(kid(resp, DAV, "href")).trim()), etag, data)); }
        catch (e) { /* ikke en gyldig VJOURNAL */ }
      });
      return notes;
    }

    // -> {etag, raw} eller null hvis den ikke findes
    async getNote(href) {
      var r = await this.request("GET", href);
      if (r.status === 404) return null;
      if (r.status !== 200) throw new DavError(r.status, "GET " + href);
      return { etag: r.headers.get("ETag") || (await this.getEtag(href)), raw: r.text };
    }

    // etag = null opretter (If-None-Match: *), ellers opdaterer (If-Match). -> {status:412} ved konflikt
    async putNote(href, raw, etag) {
      var headers = { "Content-Type": "text/calendar; charset=utf-8" };
      if (etag) headers["If-Match"] = etag; else headers["If-None-Match"] = "*";
      var r = await this.request("PUT", href, { body: raw, headers: headers });
      if (r.status === 412) return { status: 412 };
      if (r.status < 200 || r.status > 299) throw new DavError(r.status, "PUT " + href);
      return { status: r.status, etag: r.headers.get("ETag") || (await this.getEtag(href)) };
    }

    // -> "deleted" | "conflict"
    async deleteNote(href, etag) {
      var r = await this.request("DELETE", href, { headers: etag ? { "If-Match": etag } : {} });
      if (r.status === 412) return "conflict";
      if (r.status === 200 || r.status === 204 || r.status === 404) return "deleted";
      throw new DavError(r.status, "DELETE " + href);
    }

    async createNote(col, fields) {
      col = withSlash(col);
      var uid = Ical.uuid();
      var raw = Ical.newIcs(uid, fields.title, fields.body, fields.tags);
      var href = col + uid + ".ics";
      var r = await this.putNote(href, raw, null);
      if (r.status === 412) throw new DavError(412, "noten findes allerede");
      return toNote(href, r.etag, raw);
    }

    // Opdatér en eksisterende note. -> {status:412} eller {status, etag, note}
    async saveNote(note, fields) {
      var raw = Ical.updateIcs(note.raw, fields);
      if (raw === note.raw) return { status: 204, etag: note.etag, note: note };
      var r = await this.putNote(note.href, raw, note.etag);
      if (r.status === 412) return { status: 412 };
      return { status: r.status, etag: r.etag, note: toNote(note.href, r.etag, raw) };
    }
  }

  return { DavClient: DavClient, DavError: DavError, AuthError: AuthError, NetworkError: NetworkError,
    normHref: normHref, toNote: toNote };
});
