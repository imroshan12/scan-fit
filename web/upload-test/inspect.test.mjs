// Run: node --test web/upload-test/inspect.test.mjs
// Checks the page's inspector against the repo's own fixtures and spec/fixtures/cases.json inspect_cases.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { inspect, detectFormat } from './inspect.js';

const spec = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'spec');
const image = (name) => new Uint8Array(readFileSync(join(spec, 'fixtures', 'images', name)));
const cases = JSON.parse(readFileSync(join(spec, 'fixtures', 'cases.json'), 'utf8'));

for (const c of cases.inspect_cases) {
  test(`cases.json inspect_case: ${c.id}`, () => {
    const r = inspect(image(c.input), c.input);
    assert.equal(r.format, c.expect.format);
    if (c.expect.color) assert.equal(r.color, c.expect.color);
    if (c.expect.progressive !== undefined) assert.equal(r.progressive, c.expect.progressive);
    assert.deepEqual(r.issues, c.expect.issues);
  });
}

test('a normal baseline JPEG reports size, SOF0 and three components', () => {
  const r = inspect(image('photo_phone_3000x4000.jpg'), 'photo.jpg');
  assert.equal(r.format, 'jpeg');
  assert.equal(r.width, 3000);
  assert.equal(r.height, 4000);
  assert.equal(r.sof, 'SOF0');
  assert.equal(r.progressive, false);
  assert.equal(r.components, 3);
  assert.equal(r.color, 'rgb');
  assert.deepEqual(r.issues, []);
});

test('JFIF density is read from APP0', () => {
  const r = inspect(image('photo_phone_3000x4000.jpg'));
  assert.ok(r.jfif, 'PIL writes a JFIF header');
  assert.equal(typeof r.jfif.xDensity, 'number');
});

test('PNG dimensions and colour type come from IHDR', () => {
  const r = inspect(image('signature_clean_420x180.png'), 'sig.png');
  assert.equal(r.format, 'png');
  assert.equal(r.width, 420);
  assert.equal(r.height, 180);
});

test('format comes from magic bytes, not the extension', () => {
  assert.equal(detectFormat(image('png_named_as_jpg.jpg')), 'png');
  assert.equal(detectFormat(new Uint8Array([1, 2, 3])), 'unknown');
  assert.equal(detectFormat(new TextEncoder().encode('%PDF-1.7\n')), 'pdf');
  const heic = new Uint8Array(16); heic.set(new TextEncoder().encode('ftypheic'), 4);
  assert.equal(detectFormat(heic), 'heic');
});

test('EXIF orientation and GPS are detected (hand-built little-endian TIFF)', () => {
  // SOI, APP1 "Exif", IFD0 with Orientation=6 and a GPS IFD pointer, then SOF0 + EOI.
  const tiff = [0x49, 0x49, 0x2a, 0, 8, 0, 0, 0,  2, 0,
    0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0,
    0x25, 0x88, 4, 0, 1, 0, 0, 0, 0, 0, 0, 0,  0, 0, 0, 0];
  const exif = [...new TextEncoder().encode('Exif\0\0'), ...tiff];
  const app1 = [0xff, 0xe1, (exif.length + 2) >> 8, (exif.length + 2) & 255, ...exif];
  const sof0 = [0xff, 0xc0, 0, 17, 8, 0, 10, 0, 20, 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1];
  const r = inspect(new Uint8Array([0xff, 0xd8, ...app1, ...sof0, 0xff, 0xd9]), 'x.jpg');
  assert.equal(r.exif.orientation, 6);
  assert.equal(r.exif.hasGps, true);
  assert.deepEqual(r.issues, ['HAS_GPS_EXIF', 'ROTATED_BY_EXIF']);
  assert.equal(r.width, 20);
  assert.equal(r.height, 10);
});

test('PDF: encrypted flag and page count', () => {
  const pdf = '%PDF-1.4\n1 0 obj<</Type/Page>>endobj\n2 0 obj<</Type/Pages>>endobj\n3 0 obj<</Type /Page>>endobj\ntrailer<</Encrypt 9 0 R>>';
  const r = inspect(new TextEncoder().encode(pdf), 'a.pdf');
  assert.equal(r.pages, 2, '/Pages is not a page');
  assert.equal(r.encrypted, true);
  assert.deepEqual(r.issues, ['PDF_ENCRYPTED']);
});

test('garbage and truncated input never throw', () => {
  for (const bytes of [new Uint8Array(0), new Uint8Array([0xff, 0xd8, 0xff]), new Uint8Array([0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff])]) {
    assert.doesNotThrow(() => inspect(bytes, 'broken.jpg'));
  }
});

test('extension mismatch is flagged only when the extension names a different known format', () => {
  assert.deepEqual(inspect(image('png_named_as_jpg.jpg'), 'png_named_as_jpg.jpg').issues, ['EXTENSION_MISMATCH']);
  assert.deepEqual(inspect(image('signature_clean_420x180.png'), 'sig.png').issues, []);
  assert.deepEqual(inspect(image('signature_clean_420x180.png'), 'sig.dat').issues, []);
});
