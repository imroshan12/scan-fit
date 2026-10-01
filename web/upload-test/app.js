import { inspect, sha256Hex } from './inspect.js';

const $ = (id) => document.getElementById(id);
let lastReport = null;

const fmt = (n, d = 2) => Number(n).toLocaleString('en-IN', { maximumFractionDigits: d });
const SEVERITY = { HAS_GPS_EXIF: 'warn' };
const MESSAGES = {
  EXTENSION_MISMATCH: 'File name and real type differ',
  CMYK_COLOR: 'CMYK colours: many portals reject this',
  PROGRESSIVE_JPEG: 'Progressive JPEG: some portals reject this',
  HAS_GPS_EXIF: 'Carries GPS location in EXIF',
  ROTATED_BY_EXIF: 'Rotated via EXIF: may show sideways on a portal',
  HEIC_NOT_ACCEPTED: 'HEIC: most portals reject this',
  PDF_ENCRYPTED: 'Password-protected PDF',
};

async function browserDecodedSize(file) {
  if (!file.type.startsWith('image/') || typeof createImageBitmap !== 'function') return null;
  try {
    const bitmap = await createImageBitmap(file);   // applies EXIF orientation, like <img>
    const size = { width: bitmap.width, height: bitmap.height };
    bitmap.close();
    return size;
  } catch {
    return null;
  }
}

function row(dl, label, value) {
  const dt = document.createElement('dt'); dt.textContent = label;
  const dd = document.createElement('dd'); dd.textContent = value;
  dl.append(dt, dd);
}

function render(file, report, decoded, sha) {
  $('name').textContent = file.name;
  const issues = $('issues'); issues.replaceChildren();
  if (report.issues.length === 0) {
    const li = document.createElement('li'); li.className = 'ok'; li.textContent = 'No format problems found'; issues.append(li);
  }
  for (const code of report.issues) {
    const li = document.createElement('li'); li.className = SEVERITY[code] ?? 'bad'; li.textContent = `${MESSAGES[code] ?? code} (${code})`; issues.append(li);
  }
  const dl = $('facts'); dl.replaceChildren();
  row(dl, 'Format (magic bytes)', report.format);
  row(dl, 'Browser says type', file.type || '(none)');
  row(dl, 'Size', `${report.bytes.toLocaleString('en-IN')} bytes = ${fmt(report.kb)} KB`);
  if (report.width) row(dl, 'Pixels (file)', `${report.width} × ${report.height}`);
  if (decoded) {
    const swapped = report.width && (decoded.width !== report.width || decoded.height !== report.height);
    row(dl, 'Pixels (browser decoded)', `${decoded.width} × ${decoded.height}${swapped ? '  ← differs: EXIF rotation' : ''}`);
  }
  if (report.format === 'jpeg') {
    row(dl, 'JPEG type', `${report.sof}${report.progressive ? ' (progressive)' : ' (baseline)'}`);
    row(dl, 'Colour', `${report.color} (${report.components} components)`);
    row(dl, 'DPI (JFIF)', report.jfif
      ? `${report.jfif.xDensity} × ${report.jfif.yDensity} ${['no unit (aspect only)', 'dpi', 'dots/cm'][report.jfif.units] ?? ''}`
      : 'no JFIF header');
    row(dl, 'EXIF', report.exif ? `present · orientation ${report.exif.orientation ?? '–'} · GPS ${report.exif.hasGps ? 'yes' : 'no'}` : 'none');
    row(dl, 'ICC profile / XMP', `${report.hasIcc ? 'ICC yes' : 'ICC no'} · ${report.hasXmp ? 'XMP yes' : 'XMP no'}`);
  }
  if (report.format === 'png') row(dl, 'PNG', `${report.color}, ${report.bitDepth}-bit`);
  if (report.format === 'pdf') row(dl, 'PDF', `v${report.version} · ${report.pages} page(s) · ${report.encrypted ? 'encrypted' : 'not encrypted'}`);
  row(dl, 'Last modified', new Date(file.lastModified).toISOString());
  row(dl, 'SHA-256', sha);
  $('result').hidden = false;
  $('copied').hidden = true;
}

$('file').addEventListener('change', async (event) => {
  const file = event.target.files[0];
  if (!file) return;
  const bytes = new Uint8Array(await file.arrayBuffer());
  const [decoded, sha] = await Promise.all([browserDecodedSize(file), sha256Hex(bytes)]);
  const report = inspect(bytes, file.name);
  lastReport = { fileName: file.name, browserType: file.type, browserDecoded: decoded, sha256: sha, userAgent: navigator.userAgent, ...report };
  render(file, report, decoded, sha);
});

$('copy').addEventListener('click', async () => {
  if (!lastReport) return;
  try {
    await navigator.clipboard.writeText(JSON.stringify(lastReport, null, 2));
    $('copied').hidden = false;
  } catch {
    // Clipboard can be blocked (permissions, non-secure context): show the text so it can be copied by hand.
    window.prompt('Copy this report:', JSON.stringify(lastReport));
  }
});
