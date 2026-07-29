// ponytail: single Worker, zero deps, handles upload auth + download proxy + delete + Firestore ownership verification
// Secrets: B2_APP_KEY_ID, B2_APP_KEY, FIREBASE_SERVICE_ACCOUNT
// Vars:   B2_BUCKET_ID, B2_BUCKET_NAME, ALLOWED_ORIGINS

// ponytail: first allowed origin, add Origin-echoing if multi-origin needed later
function corsOrigin(env) {
  return (env?.ALLOWED_ORIGINS || 'https://vdrive-64deb.web.app').split(',')[0].trim()
}

function corsHeaders(env) {
  const origin = corsOrigin(env)
  return {
    'Access-Control-Allow-Origin': origin,
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  }
}

// ponytail: in-memory sliding-window rate limiter, resets on deploy
const rateMap = new Map()
const RATE_WINDOW = 60000
const RATE_LIMITS = { '/api/upload-url': 10, '/api/download': 30, '/api/delete': 20, '/api/code-files': 30, '/api/zip': 10, '/api/zip-delete': 6 }
const RATE_DEFAULT = 60

function checkRateLimit(request, path) {
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown'
  const slot = Math.floor(Date.now() / RATE_WINDOW)
  const key = `${ip}:${slot}`
  const max = RATE_LIMITS[path] || RATE_DEFAULT
  const count = (rateMap.get(key) || 0) + 1
  rateMap.set(key, count)
  if (rateMap.size > 1000) {
    const cutoff = slot - 2
    for (const [k] of rateMap)
      if (parseInt(k.split(':')[1]) < cutoff) rateMap.delete(k)
  }
  return count > max
}

// ponytail: per-code failed-attempt rate limit, stops targeted code guessing
function checkCodeFailedRateLimit(code) {
  if (!code) return false
  const slot = Math.floor(Date.now() / RATE_WINDOW)
  const key = `cfail:${code.toUpperCase()}:${slot}`
  const count = (rateMap.get(key) || 0) + 1
  rateMap.set(key, count)
  return count > 10
}

// ponytail: per-UID rate limit alongside IP, prevents abuse behind shared IPs (schools)
function checkUidRateLimit(uid, path) {
  if (!uid) return false
  const slot = Math.floor(Date.now() / RATE_WINDOW)
  const key = `uid:${uid}:${path}:${slot}`
  const max = RATE_LIMITS[path] || RATE_DEFAULT
  const count = (rateMap.get(key) || 0) + 1
  rateMap.set(key, count)
  if (rateMap.size > 2000) {
    const cutoff = slot - 2
    for (const [k] of rateMap)
      if (k.startsWith('uid:') && parseInt(k.split(':')[3]) < cutoff) rateMap.delete(k)
  }
  return count > max
}

function json(body, status = 200, env) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...corsHeaders(env) } })
}

// --- Firebase service account → OAuth2 token for Firestore admin access ---

let _fsToken = null
let _fsTokenExp = 0

async function getFirestoreToken(env) {
  if (_fsToken && Date.now() < _fsTokenExp) return _fsToken
  const sa = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT)
  const now = Math.floor(Date.now() / 1000)
  const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({
    iss: sa.client_email, scope: 'https://www.googleapis.com/auth/datastore',
    aud: 'https://oauth2.googleapis.com/token', exp: now + 3600, iat: now,
  }))
  const msg = header + '.' + payload
  const pem = sa.private_key.replace(/-----[^-]+-----/g, '').replace(/\s/g, '')
  const keyData = Uint8Array.from(atob(pem), c => c.charCodeAt(0))
  const key = await crypto.subtle.importKey('pkcs8', keyData, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign'])
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(msg))
  const jwt = msg + '.' + btoa(String.fromCharCode(...new Uint8Array(sig)))
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${encodeURIComponent(jwt)}`,
  })
  const data = await res.json()
  if (!res.ok) throw new Error('OAuth2 failed: ' + (data.error_description || data.error))
  _fsToken = data.access_token
  _fsTokenExp = now + (data.expires_in || 3600) * 1000 - 60000
  return _fsToken
}

async function firestoreQuery(env, collectionId, filters) {
  const token = await getFirestoreToken(env)
  const projectId = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT).project_id
  const conditions = filters.map(f => ({
    fieldFilter: { field: { fieldPath: f.field }, op: f.op, value: { [f.type]: f.value } }
  }))
  const body = { structuredQuery: { from: [{ collectionId }], where: { compositeFilter: { op: 'AND', filters: conditions } } } }
  const res = await fetch(
    `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`,
    { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
  )
  if (!res.ok) throw new Error('Firestore query failed: ' + (await res.text()))
  const results = await res.json()
  return results.filter(r => r.document).map(r => {
    const fields = r.document.fields || {}
    const doc = { id: r.document.name.split('/').pop() }
    for (const [k, v] of Object.entries(fields)) {
      const val = v.stringValue ?? v.integerValue ?? v.doubleValue ?? v.booleanValue ?? v.timestampValue ?? null
      doc[k] = val
    }
    return doc
  })
}

async function firestoreGet(env, path) {
  const token = await getFirestoreToken(env)
  const projectId = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT).project_id
  const res = await fetch(
    `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${path}`,
    { headers: { Authorization: `Bearer ${token}` } }
  )
  if (res.status === 404) return null
  if (!res.ok) throw new Error('Firestore get failed: ' + (await res.text()))
  const doc = await res.json()
  const fields = doc.fields || {}
  const result = { id: doc.name.split('/').pop() }
  for (const [k, v] of Object.entries(fields)) {
    const val = v.stringValue ?? v.integerValue ?? v.doubleValue ?? v.booleanValue ?? v.timestampValue ?? null
    result[k] = val
  }
  return result
}

// --- Ownership check helpers ---

async function verifyFileOwnership(env, fileName, userId) {
  if (!env.FIREBASE_SERVICE_ACCOUNT) { console.warn('FIREBASE_SERVICE_ACCOUNT not set, denying download'); return false }
  try {
    const files = await firestoreQuery(env, 'files', [
      { field: 'b2FileName', op: 'EQUAL', type: 'stringValue', value: fileName },
      { field: 'userId', op: 'EQUAL', type: 'stringValue', value: userId },
    ])
    return files.length > 0
  } catch (e) {
    console.warn('Ownership check failed, denying download:', e.message)
    return false
  }
}

// --- B2 ---

async function b2Authorize(env) {
  const basicAuth = btoa(`${env.B2_APP_KEY_ID}:${env.B2_APP_KEY}`)
  const res = await fetch('https://api.backblazeb2.com/b2api/v3/b2_authorize_account', {
    headers: { Authorization: `Basic ${basicAuth}` }
  })
  if (!res.ok) throw new Error('B2 auth failed: ' + (await res.text()))
  return res.json()
}

async function handleGetUploadUrl(request, env) {
  const url = new URL(request.url)
  const userId = url.searchParams.get('userId')
  const contentLength = parseInt(url.searchParams.get('contentLength') || '0')
  if (!userId) return json({ error: 'userId required' }, 400, env)
  // ponytail: read subscription doc for tier cap, fail-soft to free on error
  let maxSize = 100 * 1024 * 1024
  try {
    const sub = await firestoreGet(env, `subscriptions/${userId}`)
    if (sub && sub.status === 'active' && sub.expiresAt && new Date(sub.expiresAt) > new Date())
      maxSize = 500 * 1024 * 1024
  } catch (_) { /* fail-soft: default free tier */ }
  if (!contentLength || contentLength > maxSize) return json({ error: `File too large (max ${maxSize / (1024*1024)} MB)` }, 400, env)

  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')
  const res = await fetch(`${apiUrl}/b2api/v3/b2_get_upload_url`, {
    method: 'POST',
    headers: { Authorization: auth.authorizationToken },
    body: JSON.stringify({ bucketId: env.B2_BUCKET_ID })
  })
  if (!res.ok) throw new Error('B2 upload URL failed: ' + (await res.text()))
  const data = await res.json()
  return json({ uploadUrl: data.uploadUrl, authToken: data.authorizationToken }, 200, env)
}

async function handleDownload(request, env) {
  const { fileName, userId } = await request.json()
  if (!fileName) return json({ error: 'fileName required' }, 400, env)
  if (checkUidRateLimit(userId, '/api/download')) return json({ error: 'Too many requests' }, 429, env)
  if (userId) {
    if (!await verifyFileOwnership(env, fileName, userId))
      return json({ error: 'Access denied' }, 403, env)
  }
  // ponytail: userId optional - access page calls without it (already validated via access code)

  const auth = await b2Authorize(env)
  const downloadUrl = auth.apiInfo?.storageApi?.downloadUrl
  if (!downloadUrl) throw new Error('B2 auth: missing downloadUrl')
  const b2Res = await fetch(`${downloadUrl}/file/${env.B2_BUCKET_NAME}/${encodeURIComponent(fileName)}`, {
    headers: { Authorization: auth.authorizationToken }
  })
  const respHeaders = new Headers({
    'Content-Disposition': 'attachment',
    ...corsHeaders(env),
  })
  const ct = b2Res.headers.get('Content-Type')
  if (ct) respHeaders.set('Content-Type', ct)
  const cl = b2Res.headers.get('Content-Length')
  if (cl) respHeaders.set('Content-Length', cl)
  return new Response(b2Res.body, { status: b2Res.status, headers: respHeaders })
}

async function handleDelete(request, env) {
  const { fileId, fileName, userId } = await request.json()
  if (!userId) return json({ error: 'userId required' }, 400, env)
  if (checkUidRateLimit(userId, '/api/delete')) return json({ error: 'Too many requests' }, 429, env)
  if (!await verifyFileOwnership(env, fileName, userId))
    return json({ error: 'Access denied' }, 403, env)

  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')
  const res = await fetch(`${apiUrl}/b2api/v3/b2_delete_file_version`, {
    method: 'POST',
    headers: { Authorization: auth.authorizationToken },
    body: JSON.stringify({ fileId, fileName })
  })
  if (!res.ok) throw new Error('B2 delete failed: ' + (await res.text()))
  return new Response('ok', { status: 200, headers: corsHeaders(env) })
}

async function handleCodeFiles(request, env) {
  const { code } = await request.json()
  if (!code) return json({ error: 'code required' }, 400, env)

  const codes = await firestoreQuery(env, 'accessCodes', [
    { field: 'code', op: 'EQUAL', type: 'stringValue', value: code },
  ])
  if (codes.length === 0) {
    if (checkCodeFailedRateLimit(code)) return json({ error: 'Too many requests' }, 429, env)
    return json({ error: 'Code not found' }, 404, env)
  }
  const codeDoc = codes[0]

  // ponytail: check expiry - accepts both Timestamp string and millis number
  const exp = codeDoc.expiresAt
  const now = Date.now()
  let expired = false
  if (typeof exp === 'string') expired = new Date(exp).getTime() < now
  else if (typeof exp === 'number') expired = exp < now
  if (expired) {
    if (checkCodeFailedRateLimit(code)) return json({ error: 'Too many requests' }, 429, env)
    return json({ error: 'Code expired' }, 410, env)
  }

  let items = []
  if (codeDoc.folderId) {
    const files = await firestoreQuery(env, 'files', [
      { field: 'folderId', op: 'EQUAL', type: 'stringValue', value: codeDoc.folderId },
    ])
    items = files.filter(f => f.b2FileName).map(f => ({ name: f.name, b2FileName: f.b2FileName, size: parseInt(f.size || '0') }))
  } else if (codeDoc.fileIds) {
    let ids = codeDoc.fileIds
    if (typeof ids === 'string') ids = JSON.parse(ids)
    if (Array.isArray(ids)) {
      for (const id of ids) {
        const file = await firestoreGet(env, `files/${id}`)
        if (file && file.b2FileName) items.push({ name: file.name, b2FileName: file.b2FileName, size: parseInt(file.size || '0') })
      }
    }
  } else {
// ponytail: root-level share — no folderId/fileIds, query user's root files
    const files = await firestoreQuery(env, 'files', [
      { field: 'userId', op: 'EQUAL', type: 'stringValue', value: codeDoc.userId },
    ])
    items = files.filter(f => f.b2FileName && !f.folderId).map(f => ({ name: f.name, b2FileName: f.b2FileName, size: parseInt(f.size || '0') }))
  }
  return json({ files: items }, 200, env)
}

// --- Setup endpoints ---

async function handleSetCors(env) {
  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  const accountId = auth.accountId
  if (!apiUrl || !accountId) throw new Error('B2 auth: missing fields')
  const res = await fetch(`${apiUrl}/b2api/v3/b2_update_bucket`, {
    method: 'POST',
    headers: { Authorization: auth.authorizationToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      accountId, bucketId: env.B2_BUCKET_ID,
      corsRules: [{
        corsRuleName: 'webUpload', allowedOrigins: ['*'],
        allowedHeaders: ['Authorization', 'X-Bz-File-Name', 'X-Bz-Content-Sha1', 'Content-Type'],
        allowedOperations: ['b2_upload_file'], maxAgeSeconds: 3600,
      }]
    })
  })
  if (!res.ok) throw new Error('B2 CORS setup failed: ' + (await res.text()))
  return new Response('CORS configured', { headers: corsHeaders(env) })
}

async function handleSetLifecycle(env) {
  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  const accountId = auth.accountId
  if (!apiUrl || !accountId) throw new Error('B2 auth: missing fields')
  const res = await fetch(`${apiUrl}/b2api/v3/b2_update_bucket`, {
    method: 'POST',
    headers: { Authorization: auth.authorizationToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      accountId, bucketId: env.B2_BUCKET_ID,
      lifecycleRules: [{ fileNamePrefix: '', daysFromHidingToDeleting: 1 }]
    })
  })
  if (!res.ok) throw new Error('B2 lifecycle setup failed: ' + (await res.text()))
  return new Response('Lifecycle configured', { headers: corsHeaders(env) })
}

// --- Config ---

// ponytail: exposes ADMIN_UID so client JS doesn't hardcode it
function handleConfig(env) {
  return json({ adminUid: env.ADMIN_UID || '' }, 200, env)
}

// --- ZIP helpers ---
// ponytail: CRC32 lookup table, computed once at module load
const _crc32Table = new Uint32Array(256)
for (let i = 0; i < 256; i++) {
  let c = i
  for (let j = 0; j < 8; j++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1)
  _crc32Table[i] = c
}

function crc32OfBytes(bytes) {
  let crc = 0xFFFFFFFF
  for (let i = 0; i < bytes.length; i++) crc = _crc32Table[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8)
  return (crc ^ 0xFFFFFFFF) >>> 0
}

// Local file header with data descriptor (bit 3 set). CRC/sizes are 0 here;
// the data descriptor that follows the file data carries the real values.
function zipLocalFileHeader(name, nameBytes) {
  const buf = new ArrayBuffer(30 + nameBytes.length)
  const v = new DataView(buf)
  let o = 0
  v.setUint32(o, 0x04034b50, true); o += 4 // signature
  v.setUint16(o, 20, true); o += 2 // version needed
  v.setUint16(o, 0x0800, true); o += 2 // flag: data descriptor (bit 3)
  v.setUint16(o, 0, true); o += 2 // compression: stored
  v.setUint16(o, 0, true); o += 2 // mod time
  v.setUint16(o, 0, true); o += 2 // mod date
  v.setUint32(o, 0, true); o += 4 // CRC-32 (placeholder)
  v.setUint32(o, 0, true); o += 4 // compressed size (placeholder)
  v.setUint32(o, 0, true); o += 4 // uncompressed size (placeholder)
  v.setUint16(o, nameBytes.length, true); o += 2 // name length
  v.setUint16(o, 0, true); o += 2 // extra field length
  new Uint8Array(buf, o).set(nameBytes)
  return buf
}

// Data descriptor (follows file data when bit 3 is set)
function zipDataDescriptor(crc, compressedSize, uncompressedSize) {
  const buf = new ArrayBuffer(16)
  const v = new DataView(buf)
  v.setUint32(0, 0x08074b50, true) // signature
  v.setUint32(4, crc, true)
  v.setUint32(8, compressedSize, true)
  v.setUint32(12, uncompressedSize, true)
  return buf
}

function zipCentralDirEntry(name, nameBytes, crc, compressedSize, uncompressedSize, localHeaderOffset) {
  const buf = new ArrayBuffer(46 + nameBytes.length)
  const v = new DataView(buf)
  let o = 0
  v.setUint32(o, 0x02014b50, true); o += 4 // signature
  v.setUint16(o, 20, true); o += 2 // version made by
  v.setUint16(o, 20, true); o += 2 // version needed
  v.setUint16(o, 0x0800, true); o += 2 // flag: data descriptor
  v.setUint16(o, 0, true); o += 2 // compression: stored
  v.setUint16(o, 0, true); o += 2 // mod time
  v.setUint16(o, 0, true); o += 2 // mod date
  v.setUint32(o, crc, true); o += 4
  v.setUint32(o, compressedSize, true); o += 4
  v.setUint32(o, uncompressedSize, true); o += 4
  v.setUint16(o, nameBytes.length, true); o += 2
  v.setUint16(o, 0, true); o += 2 // extra field length
  v.setUint16(o, 0, true); o += 2 // file comment length
  v.setUint16(o, 0, true); o += 2 // disk number start
  v.setUint16(o, 0, true); o += 2 // internal file attributes
  v.setUint32(o, 0, true); o += 4 // external file attributes
  v.setUint32(o, localHeaderOffset, true); o += 4 // local header offset
  new Uint8Array(buf, o).set(nameBytes)
  return buf
}

function zipEOCD(numEntries, centralDirSize, centralDirOffset) {
  const buf = new ArrayBuffer(22)
  const v = new DataView(buf)
  v.setUint32(0, 0x06054b50, true) // signature
  v.setUint16(4, 0, true) // disk number
  v.setUint16(6, 0, true) // disk with central dir
  v.setUint16(8, numEntries, true) // entries on this disk
  v.setUint16(10, numEntries, true) // total entries
  v.setUint32(12, centralDirSize, true)
  v.setUint32(16, centralDirOffset, true)
  v.setUint16(20, 0, true) // comment length
  return buf
}

async function handleZip(request, env) {
  const body = await request.json()
  const files = body.files
  const userId = body.userId

  if (!userId) return json({ error: 'userId required' }, 400, env)
  if (!files?.length) return json({ error: 'No files selected' }, 400, env)
  if (files.length > 20) return json({ error: 'Max 20 files per ZIP' }, 400, env)
  if (checkUidRateLimit(userId, '/api/zip')) return json({ error: 'Too many requests' }, 429, env)

  // Verify ownership of each file and fetch metadata
  const fileMeta = []
  for (const f of files) {
    if (!f.b2FileName || !f.b2FileId) return json({ error: 'Missing file metadata' }, 400, env)
    if (!await verifyFileOwnership(env, f.b2FileName, userId))
      return json({ error: 'Access denied' }, 403, env)
    fileMeta.push(f)
  }

  const auth = await b2Authorize(env)
  const downloadUrl = auth.apiInfo?.storageApi?.downloadUrl
  if (!downloadUrl) throw new Error('B2 auth: missing downloadUrl')

  const encoder = new TextEncoder()
  const entries = [] // { nameBytes, crc, compressedSize, uncompressedSize, localHeaderOffset }
  let currentOffset = 0

  const { readable, writable } = new TransformStream()
  const writer = writable.getWriter()

  ;(async () => {
    try {
      for (const f of fileMeta) {
        const nameBytes = encoder.encode(f.name || 'file')
        const localHeaderOffset = currentOffset
        const localHeader = zipLocalFileHeader(f.name || 'file', nameBytes)
        await writer.write(localHeader)
        currentOffset += localHeader.byteLength

        // Fetch file from B2 and stream through CRC computation
        const b2Res = await fetch(`${downloadUrl}/file/${env.B2_BUCKET_NAME}/${encodeURIComponent(f.b2FileName)}`, {
          headers: { Authorization: auth.authorizationToken }
        })
        if (!b2Res.ok) throw new Error(`Failed to fetch ${f.name}: ${b2Res.status}`)

        const uncompressedSize = parseInt(b2Res.headers.get('Content-Length') || '0')
        const crc = crc32OfBytes(new Uint8Array(await b2Res.arrayBuffer()))

        // Re-fetch to stream data through writer (B2 response body is consumed)
        const b2Res2 = await fetch(`${downloadUrl}/file/${env.B2_BUCKET_NAME}/${encodeURIComponent(f.b2FileName)}`, {
          headers: { Authorization: auth.authorizationToken }
        })
        if (!b2Res2.ok) throw new Error(`Failed to stream ${f.name}: ${b2Res2.status}`)

        const reader = b2Res2.body.getReader()
        let bytesWritten = 0
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          await writer.write(value)
          bytesWritten += value.byteLength
        }

        const dd = zipDataDescriptor(crc, bytesWritten, bytesWritten)
        await writer.write(dd)
        currentOffset += bytesWritten + dd.byteLength

        entries.push({ nameBytes, crc, compressedSize: bytesWritten, uncompressedSize: bytesWritten, localHeaderOffset })
      }

      // Write central directory
      const cdStart = currentOffset
      for (const e of entries) {
        const cdEntry = zipCentralDirEntry(
          '', e.nameBytes, e.crc, e.compressedSize, e.uncompressedSize, e.localHeaderOffset
        )
        await writer.write(cdEntry)
        currentOffset += cdEntry.byteLength
      }

      // Write EOCD
      const eocd = zipEOCD(entries.length, currentOffset - cdStart, cdStart)
      await writer.write(eocd)

      await writer.close()
    } catch (e) {
      await writer.abort(e?.message || String(e) || 'Unknown error')
    }
  })()

  return new Response(readable, {
    status: 200,
    headers: {
      'Content-Type': 'application/zip',
      'Content-Disposition': 'attachment; filename="files.zip"',
      ...corsHeaders(env),
    }
  })
}

async function handleZipDelete(request, env) {
  const { fileIds, userId } = await request.json()
  if (!userId) return json({ error: 'userId required' }, 400, env)
  if (!fileIds?.length) return json({ error: 'No files selected' }, 400, env)
  if (checkUidRateLimit(userId, '/api/zip-delete')) return json({ error: 'Too many requests' }, 429, env)

  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  if (!apiUrl) return json({ error: 'B2 auth failed' }, 500, env)

  const results = { deleted: 0, failed: 0, errors: [] }
  for (const fileId of fileIds) {
    try {
      const doc = await firestoreGet(env, `files/${fileId}`)
      if (!doc || doc.userId !== userId) { results.failed++; results.errors.push(`${fileId}: access denied`); continue }
      if (!doc.b2FileId || !doc.b2FileName) { results.failed++; results.errors.push(`${fileId}: missing B2 metadata`); continue }
      const res = await fetch(`${apiUrl}/b2api/v3/b2_delete_file_version`, {
        method: 'POST',
        headers: { Authorization: auth.authorizationToken, 'Content-Type': 'application/json' },
        body: JSON.stringify({ fileId: doc.b2FileId, fileName: doc.b2FileName })
      })
      if (!res.ok) throw new Error('B2 delete failed: ' + (await res.text()))
      results.deleted++
    } catch (e) {
      results.failed++
      results.errors.push(`${fileId}: ${e.message}`)
    }
  }
  return json(results, 200, env)
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: corsHeaders(env) })
    const url = new URL(request.url)
    const setupPaths = ['/api/set-cors', '/api/set-lifecycle']
    if (!setupPaths.includes(url.pathname) && checkRateLimit(request, url.pathname))
      return json({ error: 'Too many requests' }, 429, env)

    try {
      if (url.pathname === '/api/config' && request.method === 'GET') return handleConfig(env)
      if (url.pathname === '/api/upload-url' && request.method === 'GET') return await handleGetUploadUrl(request, env)
      if (url.pathname === '/api/download' && request.method === 'POST') return await handleDownload(request, env)
       if (url.pathname === '/api/delete' && request.method === 'DELETE') return await handleDelete(request, env)
       if (url.pathname === '/api/code-files' && request.method === 'POST') return await handleCodeFiles(request, env)
       if (url.pathname === '/api/zip' && request.method === 'POST') return await handleZip(request, env)
       if (url.pathname === '/api/zip-delete' && request.method === 'POST') return await handleZipDelete(request, env)
       if (url.pathname === '/api/set-cors' && request.method === 'POST') return await handleSetCors(env)
       if (url.pathname === '/api/set-lifecycle' && request.method === 'POST') return await handleSetLifecycle(env)
      return json({ error: 'Not found' }, 404, env)
    } catch (e) {
      return json({ error: e.message }, 500, env)
    }
  }
}
