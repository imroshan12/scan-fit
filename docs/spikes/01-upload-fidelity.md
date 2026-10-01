# Spike 1: export → browser upload fidelity

**Question.** Which save locations and browsers change a file's bytes between ScanFit's verified export and what a portal's
upload form receives?

**Tool (built).** `web/upload-test/`: a static page that reads the picked file locally (no network, enforced by CSP) and prints
bytes, KB (1024), format from magic bytes, pixels, JPEG baseline/progressive, components, JFIF DPI, EXIF, SHA-256, and the
size the *browser* decodes. Its parser is tested against `spec/fixtures` (`node --test web/upload-test/inspect.test.mjs`).
Serve `web/` with any static host (Cloudflare Pages is planned) or `python3 -m http.server -d web`.

**Verified so far:** the page and parser work in a desktop browser (a hand-built CMYK JPEG is flagged `CMYK_COLOR`).
**Not verified:** any phone, any browser listed below.

## Protocol
For each fixture output (the 4 IBPS PO files from the app, plus `progressive_200x230.jpg` and `cmyk_photo_200x230.jpg` as controls):
1. Record the SHA-256 on the source device or computer (`shasum -a 256`).
2. Open the echo page and pick the file from each path below. Record SHA-256, bytes, pixels, JPEG type, DPI.
3. A different SHA-256 or size = that path re-encoded or rewrote the file.

| Path | Android Chrome | Samsung Internet | iOS Safari | Chrome iOS |
|---|---|---|---|---|
| Files / Downloads (`Download/ScanFit/<Exam>/`) | ☐ | ☐ | ☐ (Files) | ☐ (Files) |
| Photo Picker | ☐ | ☐ | n/a | n/a |
| Photos library | n/a | n/a | ☐ | ☐ |

**Expected (unconfirmed):** some iOS Photos paths re-encode, which is why iOS exports default to Files with a warning
(ARCHITECTURE section 7). **Decision this feeds:** whether "Save to Photos" ships at all and with what warning copy.

Results go in a table here, with device, OS and browser version for each row.
