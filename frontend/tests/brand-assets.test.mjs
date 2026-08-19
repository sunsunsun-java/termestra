import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'
import { inflateSync } from 'node:zlib'

const PUBLIC_ROOT = resolve(import.meta.dirname, '../web/public')
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])

const paeth = (left, above, upperLeft) => {
  const prediction = left + above - upperLeft
  const leftDistance = Math.abs(prediction - left)
  const aboveDistance = Math.abs(prediction - above)
  const upperLeftDistance = Math.abs(prediction - upperLeft)
  if (leftDistance <= aboveDistance && leftDistance <= upperLeftDistance) return left
  return aboveDistance <= upperLeftDistance ? above : upperLeft
}

const decodeRgbaPng = async (relativePath) => {
  const bytes = await readFile(resolve(PUBLIC_ROOT, relativePath))
  assert.deepEqual(bytes.subarray(0, 8), PNG_SIGNATURE, `${relativePath} must be a PNG`)

  let offset = 8
  let width = 0
  let height = 0
  let bitDepth = 0
  let colorType = 0
  const dataChunks = []
  while (offset < bytes.length) {
    const length = bytes.readUInt32BE(offset)
    const type = bytes.toString('ascii', offset + 4, offset + 8)
    const data = bytes.subarray(offset + 8, offset + 8 + length)
    offset += 12 + length
    if (type === 'IHDR') {
      width = data.readUInt32BE(0)
      height = data.readUInt32BE(4)
      bitDepth = data[8]
      colorType = data[9]
    } else if (type === 'IDAT') {
      dataChunks.push(data)
    } else if (type === 'IEND') {
      break
    }
  }

  assert.equal(bitDepth, 8, `${relativePath} must use 8-bit channels`)
  assert.equal(colorType, 6, `${relativePath} must use RGBA pixels`)
  const bytesPerPixel = 4
  const stride = width * bytesPerPixel
  const filtered = inflateSync(Buffer.concat(dataChunks))
  const pixels = Buffer.alloc(stride * height)
  let input = 0
  for (let y = 0; y < height; y += 1) {
    const filter = filtered[input]
    input += 1
    for (let x = 0; x < stride; x += 1) {
      const raw = filtered[input + x]
      const output = y * stride + x
      const left = x >= bytesPerPixel ? pixels[output - bytesPerPixel] : 0
      const above = y > 0 ? pixels[output - stride] : 0
      const upperLeft = y > 0 && x >= bytesPerPixel ? pixels[output - stride - bytesPerPixel] : 0
      const predictor =
        filter === 0
          ? 0
          : filter === 1
            ? left
            : filter === 2
              ? above
              : filter === 3
                ? Math.floor((left + above) / 2)
                : filter === 4
                  ? paeth(left, above, upperLeft)
                  : -1
      assert.notEqual(predictor, -1, `${relativePath} uses an unsupported PNG filter`)
      pixels[output] = (raw + predictor) & 0xff
    }
    input += stride
  }

  const pixel = (x, y) => {
    const start = (y * width + x) * bytesPerPixel
    return [...pixels.subarray(start, start + bytesPerPixel)]
  }
  return { height, pixel, pixels, width }
}

test('product-tour screenshots are real PNG files', async () => {
  for (const path of ['screenshots/1.png', 'screenshots/2.png', 'screenshots/3.png', 'screenshots/4.png']) {
    const bytes = await readFile(resolve(PUBLIC_ROOT, path))
    assert.deepEqual(bytes.subarray(0, 8), PNG_SIGNATURE, `${path} must use PNG bytes for its .png extension`)
    assert.equal(bytes.toString('ascii', 12, 16), 'IHDR', `${path} is missing a PNG image header`)
  }
})

test('brand assets expose the exact sizes used by browsers and installed apps', async () => {
  const expected = new Map([
    ['logo.png', 512],
    ['icons/icon-32.png', 32],
    ['icons/icon-192.png', 192],
    ['icons/apple-touch-icon-180.png', 180],
    ['icons/icon-512.png', 512],
    ['icons/icon-512-maskable.png', 512],
  ])

  for (const [path, size] of expected) {
    const decoded = await decodeRgbaPng(path)
    assert.deepEqual([decoded.width, decoded.height], [size, size], `${path} has the wrong size`)
  }
})

test('the topbar mark is transparent while installable icons have an opaque brand tile', async () => {
  const logo = await decodeRgbaPng('logo.png')
  assert.equal(logo.pixel(0, 0)[3], 0)
  assert.equal(logo.pixel(logo.width / 2, logo.height / 2)[3], 255)

  for (const path of [
    'icons/icon-192.png',
    'icons/apple-touch-icon-180.png',
    'icons/icon-512.png',
    'icons/icon-512-maskable.png',
  ]) {
    const icon = await decodeRgbaPng(path)
    for (let index = 3; index < icon.pixels.length; index += 4) {
      assert.equal(icon.pixels[index], 255, `${path} contains a transparent installable pixel`)
    }
    assert.deepEqual(icon.pixel(0, 0), [17, 22, 47, 255])
  }
})

test('the maskable mark remains inside the platform safe zone', async () => {
  const icon = await decodeRgbaPng('icons/icon-512-maskable.png')
  const background = icon.pixel(0, 0)
  let minX = icon.width
  let minY = icon.height
  let maxX = -1
  let maxY = -1
  for (let y = 0; y < icon.height; y += 1) {
    for (let x = 0; x < icon.width; x += 1) {
      if (icon.pixel(x, y).every((value, index) => value === background[index])) continue
      minX = Math.min(minX, x)
      minY = Math.min(minY, y)
      maxX = Math.max(maxX, x)
      maxY = Math.max(maxY, y)
    }
  }
  assert.ok(maxX >= 0, 'maskable icon must contain a visible mark')
  const safeInset = Math.floor(icon.width * 0.18)
  assert.ok(minX >= safeInset && minY >= safeInset)
  assert.ok(maxX < icon.width - safeInset && maxY < icon.height - safeInset)
})

test('the install manifest advertises the independent Termestra hero', async () => {
  const manifest = JSON.parse(await readFile(resolve(PUBLIC_ROOT, 'manifest.webmanifest'), 'utf8'))
  assert.deepEqual(manifest.screenshots, [
    {
      src: '/screenshots/termestra-promo-hero-light.png',
      sizes: '1672x941',
      type: 'image/png',
      form_factor: 'wide',
      label: 'Termestra plan, dispatch, parallel execution, and verified results',
    },
  ])
})
