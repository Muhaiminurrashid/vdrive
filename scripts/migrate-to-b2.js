// ponytail: one-time migration from fileData (base64) to B2
// Usage: B2_APP_KEY_ID=xxx B2_APP_KEY=xxx B2_BUCKET_ID=xxx node scripts/migrate-to-b2.js
// Requires GOOGLE_APPLICATION_CREDENTIALS for Firebase Admin

const admin = require('firebase-admin')

admin.initializeApp({
  credential: admin.credential.applicationDefault()
})

const B2_APP_KEY_ID = process.env.B2_APP_KEY_ID
const B2_APP_KEY = process.env.B2_APP_KEY
const B2_BUCKET_ID = process.env.B2_BUCKET_ID
const B2_BUCKET_NAME = process.env.B2_BUCKET_NAME || 'vdrive12'

async function b2Authorize() {
  const res = await fetch('https://api.backblazeb2.com/b2api/v3/b2_authorize_account', {
    headers: { Authorization: 'Basic ' + Buffer.from(`${B2_APP_KEY_ID}:${B2_APP_KEY}`).toString('base64') }
  })
  return res.json()
}

async function b2Upload(auth, base64Data, fileName, contentType) {
  // Get upload URL
  const urlRes = await fetch(`${auth.apiUrl}/b2api/v3/b2_get_upload_url`, {
    method: 'POST',
    headers: { Authorization: auth.authorizationToken },
    body: JSON.stringify({ bucketId: B2_BUCKET_ID })
  })
  const { uploadUrl, authorizationToken } = await urlRes.json()

  // Upload file
  const buf = Buffer.from(base64Data, 'base64')
  const uploadRes = await fetch(uploadUrl, {
    method: 'POST',
    headers: {
      Authorization: authorizationToken,
      'X-Bz-File-Name': encodeURIComponent(fileName),
      'Content-Type': contentType || 'application/octet-stream',
      'X-Bz-Content-Sha1': 'do_not_verify'
    },
    body: buf
  })
  return uploadRes.json()
}

async function migrate() {
  const auth = await b2Authorize()
  console.log('Authenticated with B2')

  const db = admin.firestore()
  const fileDataDocs = await db.collection('fileData').get()
  const total = fileDataDocs.docs.length
  console.log(`Found ${total} fileData docs to migrate`)

  let done = 0
  for (const doc of fileDataDocs.docs) {
    const fileId = doc.id
    const { data } = doc.data()
    if (!data) { console.log(`  skip ${fileId}: no data`); continue }

    const fileDoc = await db.collection('files').doc(fileId).get()
    if (!fileDoc.exists) { console.log(`  skip ${fileId}: no matching files doc`); continue }

    const fileData = fileDoc.data()
    const fileName = `${fileData.userId}_${Date.now()}_${fileData.name}`

    try {
      const result = await b2Upload(auth, data, fileName, fileData.type)
      await db.collection('files').doc(fileId).update({
        b2FileId: result.fileId,
        b2FileName: fileName
      })
      await db.collection('fileData').doc(fileId).delete()
      done++
      if (done % 10 === 0) console.log(`  ${done}/${total} migrated`)
    } catch (e) {
      console.error(`  FAIL ${fileId}: ${e.message}`)
    }
  }

  console.log(`Done: ${done}/${total} migrated`)
}

migrate().catch(console.error)
