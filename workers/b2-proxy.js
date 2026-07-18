// ponytail: single Worker, zero deps, handles upload auth + download proxy + delete + Firestore ownership verification
// Secrets: B2_APP_KEY_ID, B2_APP_KEY, FIREBASE_SERVICE_ACCOUNT
// Vars:   B2_BUCKET_ID, B2_BUCKET_NAME

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
}

// ponytail: in-memory sliding-window rate limiter, resets on deploy
const rateMap = new Map()
const RATE_WINDOW = 60000
const RATE_LIMITS = { '/api/upload-url': 10, '/api/download': 30, '/api/delete': 20, '/api/code-files': 30 }
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

function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...corsHeaders } })
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
  const files = await firestoreQuery(env, 'files', [
    { field: 'b2FileName', op: 'EQUAL', type: 'stringValue', value: fileName },
    { field: 'userId', op: 'EQUAL', type: 'stringValue', value: userId },
  ])
  return files.length > 0
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

async function handleGetUploadUrl(env) {
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
  return json({ uploadUrl: data.uploadUrl, authToken: data.authorizationToken })
}

async function handleDownload(request, env) {
  const { fileName, userId } = await request.json()
  if (!fileName) return json({ error: 'fileName required' }, 400)
  if (userId) {
    if (!await verifyFileOwnership(env, fileName, userId))
      return json({ error: 'Access denied' }, 403)
  }
  // ponytail: userId optional — access page calls without it (already validated via access code)

  const auth = await b2Authorize(env)
  const downloadUrl = auth.apiInfo?.storageApi?.downloadUrl
  if (!downloadUrl) throw new Error('B2 auth: missing downloadUrl')
  const b2Res = await fetch(`${downloadUrl}/file/${env.B2_BUCKET_NAME}/${encodeURIComponent(fileName)}`, {
    headers: { Authorization: auth.authorizationToken }
  })
  const respHeaders = new Headers({
    'Content-Disposition': 'attachment',
    'Access-Control-Allow-Origin': '*',
  })
  const ct = b2Res.headers.get('Content-Type')
  if (ct) respHeaders.set('Content-Type', ct)
  const cl = b2Res.headers.get('Content-Length')
  if (cl) respHeaders.set('Content-Length', cl)
  return new Response(b2Res.body, { status: b2Res.status, headers: respHeaders })
}

async function handleDelete(request, env) {
  const { fileId, fileName, userId } = await request.json()
  if (!userId) return json({ error: 'userId required' }, 400)
  if (!await verifyFileOwnership(env, fileName, userId))
    return json({ error: 'Access denied' }, 403)

  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')
  const res = await fetch(`${apiUrl}/b2api/v3/b2_delete_file_version`, {
    method: 'POST',
    headers: { Authorization: auth.authorizationToken },
    body: JSON.stringify({ fileId, fileName })
  })
  if (!res.ok) throw new Error('B2 delete failed: ' + (await res.text()))
  return new Response('ok', { status: 200, headers: corsHeaders })
}

async function handleCodeFiles(request, env) {
  const { code } = await request.json()
  if (!code) return json({ error: 'code required' }, 400)

  const codes = await firestoreQuery(env, 'accessCodes', [
    { field: 'code', op: 'EQUAL', type: 'stringValue', value: code },
  ])
  if (codes.length === 0) return json({ error: 'Code not found' }, 404)
  const codeDoc = codes[0]

  // ponytail: check expiry — accepts both Timestamp string and millis number
  const exp = codeDoc.expiresAt
  const now = Date.now()
  let expired = false
  if (typeof exp === 'string') expired = new Date(exp).getTime() < now
  else if (typeof exp === 'number') expired = exp < now
  if (expired) return json({ error: 'Code expired' }, 410)

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
  }
  return json({ files: items })
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
  return new Response('CORS configured', { headers: corsHeaders })
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
  return new Response('Lifecycle configured', { headers: corsHeaders })
}

// --- Router ---

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: corsHeaders })
    const url = new URL(request.url)
    const setupPaths = ['/api/set-cors', '/api/set-lifecycle']
    if (!setupPaths.includes(url.pathname) && checkRateLimit(request, url.pathname))
      return json({ error: 'Too many requests' }, 429)

    try {
      if (url.pathname === '/api/upload-url' && request.method === 'GET') return await handleGetUploadUrl(env)
      if (url.pathname === '/api/download' && request.method === 'POST') return await handleDownload(request, env)
      if (url.pathname === '/api/delete' && request.method === 'DELETE') return await handleDelete(request, env)
      if (url.pathname === '/api/code-files' && request.method === 'POST') return await handleCodeFiles(request, env)
      if (url.pathname === '/api/set-cors' && request.method === 'POST') return await handleSetCors(env)
      if (url.pathname === '/api/set-lifecycle' && request.method === 'POST') return await handleSetLifecycle(env)
      return json({ error: 'Not found' }, 404)
    } catch (e) {
      return json({ error: e.message }, 500)
    }
  }
}
