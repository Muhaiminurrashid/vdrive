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
├── DESIGN.md          # Navy & Cool visual spec (cool gray canvas, navy brand)
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
/users/{userId}               — { email, displayName, createdAt }
/files/{fileId}               — { name, size, type, userId, folderId?, b2FileId, b2FileName, createdAt }
/folders/{folderId}           — { name, userId, parentId?, createdAt, updatedAt }
/accessCodes/{code}           — { code, userId, fileIds[], expiresAt }
```

## Architecture Decisions

- **Files in Backblaze B2** — upload via Cloudflare Worker (auth proxy), direct download from public B2 URL. 10 GB free, 10 TB max file size. No more 1 MiB Firestore doc limit.
- **Folders** — `folders` collection active. Hierarchical (parentId). Breadcrumb navigation (Google Drive-style): "My Files > Folder > Subfolder". Sub-folders shown in file list above files, clickable to navigate deeper. Create/delete writes `parentId`.
- **Google Sign-In**: Web (popup) + Android (Credential Manager + GetGoogleIdOption) both working.
- **Storage bar**: computed from `size` field sum over ALL user files (global, not per-folder). Formatted text "X MB / 1 GB" + percentage bar.
- **Access code algorithm**: 6 chars from "ABCDEFGHJKLMNPQRSTUVWXYZ23456789". 15 min TTL. No auth check — student enters code, fetches shared files.
- **Firestore rules**: Open read/write (dev mode, expires Aug 2026). Needs auth-gated rules before production.

## Design System

See DESIGN.md for full spec.

- **Canvas**: cool gray `#f4f5f6`
- **Primary**: navy `#3B5C9A` (brand + CTAs)
- **Typography**: Inter only
- **Surfaces**: cool cards, deep navy product mockups, navy callout bands

## Conventions

- Android: Hilt DI, ViewModel + StateFlow, Navigation Compose sealed routes
- Web: Firebase CDN SDK, vanilla JS global state, CSS custom properties
- Commits: conventional commits (feat/fix/chore)
- No unit tests yet — add with `testing-setup` skill
- Code review: `ponytail-review` for over-engineering audit

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, invoke the `skill` tool with `skill: "graphify"` before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
