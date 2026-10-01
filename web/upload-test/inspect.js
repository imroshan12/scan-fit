// Byte-level file inspector for the upload echo page. It mirrors the Inspector contract in
// spec/ALGORITHMS.md section 5: format from magic bytes (never the extension), JPEG marker walk,
// PNG IHDR, PDF header heuristics. Pure functions over a Uint8Array: no DOM, no network.
// Tested in Node (inspect.test.mjs) against spec/fixtures; used by index.html in the browser.

const u16 = (b, o) => (b[o] << 8) | b[o + 1];
const ascii = (b, o, n) => String.fromCharCode(...b.subarray(o, o + n));
const startsWith = (b, bytes, o = 0) => bytes.every((v, i) => b[o + i] === v);

export function detectFormat(b) {
  if (startsWith(b, [0xff, 0xd8, 0xff])) return 'jpeg';
  if (startsWith(b, [0x89, 0x50, 0x4e, 0x47])) return 'png';
  if (ascii(b, 0, 5) === '%PDF-') return 'pdf';
  if (b.length >= 12 && ascii(b, 4, 4) === 'ftyp' && ['heic', 'heix', 'mif1', 'heim', 'heis'].includes(ascii(b, 8, 4))) return 'heic';
  if (b.length >= 12 && ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 4) === 'WEBP') return 'webp';
  return 'unknown';
}

/** Reads TIFF/EXIF just far enough for the orientation tag and the presence of a GPS IFD. */
function parseExif(b, start, end) {
  const out = { orientation: null, hasGps: false };
  if (ascii(b, start, 6) !== 'Exif\0\0') return out;
  const t = start + 6;
  const little = ascii(b, t, 2) === 'II';
  if (!little && ascii(b, t, 2) !== 'MM') return out;
  const r16 = (o) => (little ? b[o] | (b[o + 1] << 8) : u16(b, o));
  const r32 = (o) => (little ? (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0
    : ((b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]) >>> 0);
  const ifd0 = t + r32(t + 4);
  if (ifd0 + 2 > end) return out;
  const n = r16(ifd0);
  for (let i = 0; i < n && ifd0 + 2 + i * 12 + 12 <= end; i++) {
    const e = ifd0 + 2 + i * 12;
    const tag = r16(e);
    if (tag === 0x0112) out.orientation = r16(e + 8);
    if (tag === 0x8825) out.hasGps = true;
  }
  return out;
}

export function inspectJpeg(b) {
  const r = {
    progressive: false, components: null, width: null, height: null, sof: null,
    jfif: null, exif: null, hasIcc: false, hasXmp: false, adobe: false, segments: [],
  };
  let o = 2;
  while (o + 4 <= b.length) {
    if (b[o] !== 0xff) { o++; continue; }
    const m = b[o + 1];
    if (m === 0xff) { o++; continue; }                       // fill byte
    if (m === 0xd8 || m === 0x01 || (m >= 0xd0 && m <= 0xd7)) { o += 2; continue; }  // no length
    if (m === 0xd9) break;                                    // EOI
    const len = u16(b, o + 2);
    const body = o + 4;
    const end = o + 2 + len;
    r.segments.push({ marker: m.toString(16).toUpperCase(), length: len });
    if (m === 0xe0 && ascii(b, body, 5) === 'JFIF\0') {
      r.jfif = { units: b[body + 7], xDensity: u16(b, body + 8), yDensity: u16(b, body + 10) };
    } else if (m === 0xe1) {
      if (ascii(b, body, 6) === 'Exif\0\0') r.exif = parseExif(b, body, end);
      else if (ascii(b, body, 28).startsWith('http://ns.adobe.com/xap')) r.hasXmp = true;
    } else if (m === 0xe2 && ascii(b, body, 11) === 'ICC_PROFILE') {
      r.hasIcc = true;
    } else if (m === 0xee && ascii(b, body, 5) === 'Adobe') {
      r.adobe = true;
    } else if (m >= 0xc0 && m <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(m)) {
      r.sof = 'SOF' + (m - 0xc0);
      r.progressive = m === 0xc2 || m === 0xc6 || m === 0xca || m === 0xce;
      r.height = u16(b, body + 1);
      r.width = u16(b, body + 3);
      r.components = b[body + 5];
    } else if (m === 0xda) {
      break;                                                  // SOS: pixel data follows
    }
    o = end;
  }
  r.color = r.components === 1 ? 'gray' : r.components === 3 ? 'rgb' : r.components === 4 ? 'cmyk' : 'unknown';
  return r;
}

export function inspectPng(b) {
  const types = { 0: 'gray', 2: 'rgb', 3: 'indexed', 4: 'gray+alpha', 6: 'rgba' };
  const view = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return { width: view.getUint32(16), height: view.getUint32(20), bitDepth: b[24], color: types[b[25]] ?? 'unknown' };
}

export function inspectPdf(b) {
  // Latin-1 view is enough for the markers we look for, and never throws on binary streams.
  let text = '';
  for (let i = 0; i < b.length; i += 65536) text += String.fromCharCode(...b.subarray(i, Math.min(i + 65536, b.length)));
  return {
    version: text.slice(5, 8),
    encrypted: /\/Encrypt\b/.test(text),
    pages: (text.match(/\/Type\s*\/Page(?![s\w])/g) || []).length,
  };
}

const EXT_FORMAT = { jpg: 'jpeg', jpeg: 'jpeg', png: 'png', pdf: 'pdf', heic: 'heic', heif: 'heic', webp: 'webp' };

/** @returns {object} format, size, per-format details and issues (ALGORITHMS section 5 names). */
export function inspect(bytes, fileName = '') {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const format = detectFormat(b);
  const report = { format, bytes: b.length, kb: b.length / 1024, issues: [] };
  if (format === 'jpeg') Object.assign(report, inspectJpeg(b));
  if (format === 'png') Object.assign(report, inspectPng(b));
  if (format === 'pdf') Object.assign(report, inspectPdf(b));

  const ext = fileName.includes('.') ? fileName.split('.').pop().toLowerCase() : '';
  if (ext && EXT_FORMAT[ext] && EXT_FORMAT[ext] !== format) report.issues.push('EXTENSION_MISMATCH');
  if (format === 'jpeg') {
    if (report.color === 'cmyk') report.issues.push('CMYK_COLOR');
    if (report.progressive) report.issues.push('PROGRESSIVE_JPEG');
    if (report.exif?.hasGps) report.issues.push('HAS_GPS_EXIF');
    if (report.exif?.orientation && report.exif.orientation !== 1) report.issues.push('ROTATED_BY_EXIF');
  }
  if (format === 'heic') report.issues.push('HEIC_NOT_ACCEPTED');
  if (format === 'pdf' && report.encrypted) report.issues.push('PDF_ENCRYPTED');
  return report;
}

export async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(digest)].map((x) => x.toString(16).padStart(2, '0')).join('');
}
