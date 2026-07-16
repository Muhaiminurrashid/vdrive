// ponytail: single Worker, zero deps, handles upload auth + signed download + delete
// Deploy: wrangler deploy or paste in Cloudflare dashboard
// Secrets: B2_APP_KEY_ID, B2_APP_KEY
// Vars:   B2_BUCKET_ID, B2_BUCKET_NAME

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Bz-File-Name, X-Bz-Content-Sha1',
}

// ponytail: in-memory sliding-window rate limiter, resets on deploy
const rateMap = new Map()
const RATE_WINDOW = 60000
const RATE_LIMITS = {
  '/api/upload-url': 10,
  '/api/download-url': 30,
  '/api/download': 30,
  '/api/delete': 20,
}
const RATE_DEFAULT = 60

function checkRateLimit(request, path) {
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown'
  const slot = Math.floor(Date.now() / RATE_WINDOW)
  const key = `${ip}:${slot}`
  const max = RATE_LIMITS[path] || RATE_DEFAULT
  const count = (rateMap.get(key) || 0) + 1
  rateMap.set(key, count)
  // ponytail: sweep stale entries every 1k
  if (rateMap.size > 1000) {
    const cutoff = slot - 2
    for (const [k, v] of rateMap)
      if (parseInt(k.split(':')[1]) < cutoff) rateMap.delete(k)
  }
  return count > max
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: corsHeaders })

    const url = new URL(request.url)
    // ponytail: exempt setup endpoints from rate limit
    const setupPaths = ['/api/set-cors', '/api/set-lifecycle']
    if (!setupPaths.includes(url.pathname) && checkRateLimit(request, url.pathname))
      return new Response('Too many requests', { status: 429, headers: { 'Retry-After': '60', ...corsHeaders } })

    try {
      if (url.pathname === '/api/upload-url' && request.method === 'GET') return await handleGetUploadUrl(env)
      if (url.pathname === '/api/download-url' && request.method === 'GET') return await handleDownloadUrl(request, env)
      if (url.pathname === '/api/download' && request.method === 'POST') return await handleDownload(request, env)
      if (url.pathname === '/api/delete' && request.method === 'DELETE') return await handleDelete(request, env)
      if (url.pathname === '/api/set-cors' && request.method === 'POST') return await handleSetCors(env)
      if (url.pathname === '/api/set-lifecycle' && request.method === 'POST') return await handleSetLifecycle(env)
      return new Response('Not found', { status: 404, headers: corsHeaders })
    } catch (e) {
      return new Response(e.message, { status: 500, headers: corsHeaders })
    }
  }
}

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
  const authToken = auth.authorizationToken
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')

  const res = await fetch(`${apiUrl}/b2api/v3/b2_get_upload_url`, {
    method: 'POST',
    headers: { Authorization: authToken },
    body: JSON.stringify({ bucketId: env.B2_BUCKET_ID })
  })
  if (!res.ok) throw new Error('B2 upload URL failed: ' + (await res.text()))
  const data = await res.json()
  return new Response(JSON.stringify({
    uploadUrl: data.uploadUrl,
    authToken: data.authorizationToken
  }), { headers: { 'Content-Type': 'application/json', ...corsHeaders } })
}

async function handleDownloadUrl(request, env) {
  const fileName = new URL(request.url).searchParams.get('fileName')
  if (!fileName) return new Response('fileName required', { status: 400, headers: corsHeaders })

  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  const downloadUrl = auth.apiInfo?.storageApi?.downloadUrl
  const authToken = auth.authorizationToken
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')
  if (!downloadUrl) throw new Error('B2 auth: missing downloadUrl')

  const res = await fetch(`${apiUrl}/b2api/v3/b2_get_download_authorization`, {
    method: 'POST',
    headers: { Authorization: authToken },
    body: JSON.stringify({
      bucketId: env.B2_BUCKET_ID,
      fileNamePrefix: fileName,
      validDurationInSeconds: 3600
    })
  })
  if (!res.ok) throw new Error('B2 download auth failed: ' + (await res.text()))
  const data = await res.json()
  const signedUrl = `${downloadUrl}/file/${env.B2_BUCKET_NAME}/${encodeURIComponent(fileName)}?Authorization=${data.authorizationToken}`
  return new Response(JSON.stringify({ url: signedUrl }), {
    headers: { 'Content-Type': 'application/json', ...corsHeaders }
  })
}

async function handleDownload(request, env) {
  const { fileName } = await request.json()
  if (!fileName) return new Response('fileName required', { status: 400, headers: corsHeaders })

  const auth = await b2Authorize(env)
  const downloadUrl = auth.apiInfo?.storageApi?.downloadUrl
  const authToken = auth.authorizationToken
  if (!downloadUrl) throw new Error('B2 auth: missing downloadUrl')

  const b2Res = await fetch(`${downloadUrl}/file/${env.B2_BUCKET_NAME}/${encodeURIComponent(fileName)}`, {
    headers: { Authorization: authToken }
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
  const { fileId, fileName } = await request.json()
  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  const authToken = auth.authorizationToken
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')

  const res = await fetch(`${apiUrl}/b2api/v3/b2_delete_file_version`, {
    method: 'POST',
    headers: { Authorization: authToken },
    body: JSON.stringify({ fileId, fileName })
  })
  if (!res.ok) throw new Error('B2 delete failed: ' + (await res.text()))
  return new Response('ok', { headers: corsHeaders })
}

async function handleSetCors(env) {
  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  const authToken = auth.authorizationToken
  const accountId = auth.accountId
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')
  if (!accountId) throw new Error('B2 auth: missing accountId')

  const res = await fetch(`${apiUrl}/b2api/v3/b2_update_bucket`, {
    method: 'POST',
    headers: { Authorization: authToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      accountId,
      bucketId: env.B2_BUCKET_ID,
      corsRules: [{
        corsRuleName: 'webUpload',
        allowedOrigins: ['*'],
        allowedHeaders: ['Authorization', 'X-Bz-File-Name', 'X-Bz-Content-Sha1', 'Content-Type'],
        allowedOperations: ['b2_upload_file'],
        maxAgeSeconds: 3600
      }]
    })
  })
  if (!res.ok) throw new Error('B2 CORS setup failed: ' + (await res.text()))
  return new Response('CORS configured', { headers: corsHeaders })
}

async function handleSetLifecycle(env) {
  const auth = await b2Authorize(env)
  const apiUrl = auth.apiInfo?.storageApi?.apiUrl
  const authToken = auth.authorizationToken
  const accountId = auth.accountId
  if (!apiUrl) throw new Error('B2 auth: missing apiUrl')
  if (!accountId) throw new Error('B2 auth: missing accountId')

  const res = await fetch(`${apiUrl}/b2api/v3/b2_update_bucket`, {
    method: 'POST',
    headers: { Authorization: authToken, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      accountId,
      bucketId: env.B2_BUCKET_ID,
      lifecycleRules: [{
        fileNamePrefix: '',
        daysFromHidingToDeleting: 1
      }]
    })
  })
  if (!res.ok) throw new Error('B2 lifecycle setup failed: ' + (await res.text()))
  return new Response('Lifecycle configured', { headers: corsHeaders })
}
