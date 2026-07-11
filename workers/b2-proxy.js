// ponytail: single Worker, zero deps, handles upload auth + signed download + delete
// Deploy: wrangler deploy or paste in Cloudflare dashboard
// Secrets: B2_APP_KEY_ID, B2_APP_KEY
// Vars:   B2_BUCKET_ID, B2_BUCKET_NAME

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Bz-File-Name, X-Bz-Content-Sha1',
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { headers: corsHeaders })

    const url = new URL(request.url)
    try {
      if (url.pathname === '/api/upload-url' && request.method === 'GET') return await handleGetUploadUrl(env)
      if (url.pathname === '/api/download-url' && request.method === 'GET') return await handleDownloadUrl(request, env)
      if (url.pathname === '/api/delete' && request.method === 'DELETE') return await handleDelete(request, env)
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
