PONYTAIL MODE ACTIVE
Caveman mode full

Required skills:
- android-kotlin (Android Compose + Hilt + Firebase)
- mobile-android-design (Material 3, user flows)
- nextjs-app-router-patterns (web frontend)
- frontend-design (UI polish web)
- web-design-guidelines
- firebase (Auth, Firestore, Hosting)

# Virtual Pendrive

Cloud-based virtual USB drive for classrooms. Teachers upload files, generate 6-char access codes (15 min expiry), share with students. Students enter code on any PC — no account needed.

## Project Structure

```
vdrive/
├── android/          # Kotlin + Jetpack Compose app
│   └── app/src/main/java/com/vdrive/app/
│       ├── MainActivity.kt, VDriveApplication.kt
│       ├── ui/
│       │   ├── auth/       LoginScreen, AuthViewModel
│       │   ├── files/      DashboardScreen, DashboardViewModel
│       │   └── theme/      Theme, Color, Type
│       ├── data/
│       │   ├── firebase/   FirebaseService
│       │   └── repository/ AuthRepository, FileRepository
│       └── di/             AppModule (Hilt)
├── web/public/        # Vanilla HTML/CSS/JS + Firebase SDK
│   ├── index.html         Marketing landing page
│   ├── login.html         Auth (email/password, Google)
│   ├── dashboard.html     File CRUD, share code gen
│   ├── access.html        Code entry → file download
│   ├── reset-password.html
│   └── styles.css         Design system
├── workers/           # Cloudflare Worker (B2 upload proxy)
├── DESIGN.md          # Claude-inspired visual spec (warm cream canvas, coral accent)
├── firebase.json
├── firestore.rules
└── opencode.json
```

## Tech Stack

| Layer | Android | Web |
|-------|---------|-----|
| Language | Kotlin | Vanilla JS |
| UI | Jetpack Compose + M3 | HTML/CSS |
| DI | Hilt | — |
| Auth | Firebase Auth | Firebase Auth SDK |
| DB | Firestore | Firestore SDK |
| Storage | Backblaze B2 via Cloudflare Worker | Backblaze B2 (public bucket) |
| Navigation | Navigation Compose | — |

## Firestore Data Model

```
/users/{userId}          — { email, displayName, createdAt }
/files/{fileId}          — { name, size, type, userId, b2FileId, b2FileName, createdAt }
/accessCodes/{code}      — { code, userId, fileIds[], expiresAt }
```

## Architecture Decisions

- **Files in Backblaze B2** — upload via Cloudflare Worker (auth proxy), direct download from public B2 URL. 10 GB free, 10 TB max file size. No more 1 MiB Firestore doc limit.
- **No folders** — Folder model defined but unused. Flat file list.
- **Google Sign-In**: Web works (popup). Android stub — "coming soon".
- **Storage bar**: `storagePercent` hardcoded 0. Needs sum query over `files` collection.
- **Access code algorithm**: 6 chars from "ABCDEFGHJKLMNPQRSTUVWXYZ23456789". 15 min TTL. No auth check — student enters code, fetches shared files.
- **Firestore rules**: Open read/write (dev mode, expires Aug 2026). Needs auth-gated rules before production.

## Design System

See DESIGN.md for full Claude-inspired spec.

- **Canvas**: warm cream `#faf9f5`
- **Primary**: navy `#3B5C9A` (app), coral `#cc785c` (inspired by Claude DESIGN.md)
- **Typography**: serif display ("Copernicus" / Tiempos Headline) + sans body (Inter)
- **Surfaces**: cream cards, dark navy product mockups, coral callout bands

## Conventions

- Android: Hilt DI, ViewModel + StateFlow, Navigation Compose sealed routes
- Web: Firebase CDN SDK, vanilla JS global state, CSS custom properties
- Commits: conventional commits (feat/fix/chore)
- No unit tests yet — add with `testing-setup` skill
- Code review: `ponytail-review` for over-engineering audit
