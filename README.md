# Virtual Pendrive

Cloud-based virtual USB drive for classrooms. Teachers upload files, generate 6-character access codes (15-minute expiry), and share them with students. Students enter the code on any PC — no account required.

**Live web app**: [vdrive-64deb.web.app](https://vdrive-64deb.web.app)

## Why

Teachers constantly swap files with students via USB drives, email, or chat apps — slow, flaky, and accounts required. Virtual Pendrive makes file distribution instant: upload once, share a short code, done. Students just open a URL, type the code, download.

## Features

- **File upload & management** — upload, rename, delete, folder organization (Google Drive-style breadcrumbs)
- **Access codes** — 6-char codes from an ambiguity-free alphabet, 15-minute TTL, no student account needed
- **Storage quota** — visible per-user usage bar (1 GB free tier)
- **Multi-platform** — Android app (Kotlin + Jetpack Compose) and web app (vanilla HTML/CSS/JS + Firebase SDK)
- **Secure storage** — files in Backblaze B2, uploaded through a Cloudflare Worker auth proxy; Firestore never stores file bytes
- **Google Sign-In** — one-tap login on both web and Android

## Architecture

```
vdrive/
├── android/          # Kotlin + Jetpack Compose app (Hilt DI, Navigation Compose)
│   └── app/src/main/java/com/vdrive/app/
│       ├── ui/           # Login, Dashboard, theme
│       ├── data/         # FirebaseService, AuthRepository, FileRepository
│       └── di/           # Hilt modules
├── web/public/       # Vanilla HTML/CSS/JS + Firebase SDK
│   ├── index.html       # Marketing landing page
│   ├── login.html       # Auth (email/password, Google)
│   ├── dashboard.html   # File CRUD, share code generation
│   ├── access.html      # Code entry → file download
│   └── styles.css       # Design system (navy + cool gray)
├── workers/          # Cloudflare Worker — Backblaze B2 upload proxy
├── firebase.json     # Firebase Hosting + Firestore config
└── firestore.rules   # Auth-gated per-user rules
```

| Layer | Android | Web |
|-------|---------|-----|
| Language | Kotlin | Vanilla JS |
| UI | Jetpack Compose + Material 3 | HTML/CSS |
| DI | Hilt | — |
| Auth | Firebase Auth | Firebase Auth SDK |
| Database | Firestore | Firestore SDK |
| Storage | Backblaze B2 via Cloudflare Worker | Backblaze B2 |

### Data model

```
/users/{userId}               - { email, displayName, createdAt }
/files/{fileId}               - { name, size, type, userId, folderId?, b2FileId, b2FileName, createdAt }
/folders/{folderId}           - { name, userId, parentId?, createdAt, updatedAt }
/accessCodes/{code}           - { code, userId, fileIds[], expiresAt }
```

## Getting started

### Android app

Requires: JDK 17, Android SDK, a Firebase project with `google-services.json` (copy `android/app/google-services.json.example` and fill in your values).

```bash
cd android
./gradlew assembleDebug        # debug APK
./gradlew app:testDebugUnitTest  # unit tests
```

### Web app

Requires: Node.js. Configure your Firebase project in `web/public/*.html` (Firebase SDK config), then:

```bash
cd web
npm ci
npm run css       # build Tailwind CSS
firebase serve    # local dev server
firebase deploy --only hosting
```

### Cloudflare Worker

The B2 upload proxy needs `B2_APPLICATION_KEY_ID` and `B2_APPLICATION_KEY` secrets set via `wrangler secret put`. Deploy:

```bash
cd workers
npx wrangler deploy
```

## License

[MIT](LICENSE)
