# Virtual Pendrive — Project Status

## What's Built

- **Web**: Vanilla HTML/CSS/JS app with Firebase Auth + Firestore. File upload/download/delete, access code sharing (6-char, 15 min TTL), marketing landing page. Drag-drop upload. File size display. Flat folder filter.
- **Android**: Kotlin + Jetpack Compose + Hilt app. Same features as web. Google Sign-In works on device. Folder chips + create/delete. File size display.
- **Storage**: Backblaze B2 (private bucket `vdrive12`) via Cloudflare Worker proxy. Files stored on B2, metadata in Firestore.
- **Web deployed**: Firebase Hosting → https://vdrive-64deb.web.app
- **Worker deployed**: `b2-proxy` at `https://b2-proxy.muhaiminurrashid99.workers.dev`

## Architecture

```text
Client → Cloudflare Worker (upload auth, signed download, delete, rate-limited) → Backblaze B2
Client → Firestore (file metadata, access codes, users, folders)
Client → B2 directly via signed URL (download, CORS-enabled)
```

## Completed

### Phase 0 — Infrastructure
- **Worker deployed** (`wrangler deploy`) at `https://b2-proxy.muhaiminurrashid99.workers.dev`
- **Secrets set**: `B2_APP_KEY_ID`, `B2_APP_KEY`
- **`B2_PROXY_URL` updated** in all 4 files (web dashboard.html, web access.html, Android FileRepository.kt, Android DashboardViewModel.kt)

### Phase 1 — Security
- **Firestore native database created** (nam5, Spark plan — no billing)
- **Auth-gated rules deployed**:
  - `/users/{userId}` — self only
  - `/files/{fileId}` — owner read/write/create
  - `/accessCodes/{code}` — anyone read (student code entry), owner write
  - `/folders/{folderId}` — owner read/write/create
  - Catch-all deny

### Phase 2 — Storage Bar & File Size Limit
- **Web storage bar**: computed from loaded files in `loadFiles()`, displays bar + formatted size text
- **Android storage bar**: sums `size` from snapshot → `storagePercent` replaces hardcoded 0f
- **File size limit (100 MB)**: web checks in `handleUpload()`, Android throws in `FileRepository.uploadFile()`
- **Android error state**: added `error: String?` to `DashboardUiState` + SnackbarHost in screen

### Phase 3 — Android Google Sign-In
- **Dependencies added**: `credentials:1.2.2`, `credentials-play-services-auth`, `googleid:1.1.1`
- **`google-services.json` updated**: OAuth clients (Android + web) now present
- **`FirebaseService.signInWithGoogle(idToken)`**: exchanges Google ID token via `GoogleAuthProvider`
- **`AuthViewModel.signInWithGoogle(activity)`**: uses Credential Manager → `GetGoogleIdOption` → Firebase sign-in
- **SHA-1 fingerprints registered**: debug (`6B:5C:33:FB:0F:D0:8F:0E:91:5B:76:48:B7:7F:C8:7C:E0:B7:CB:A7`) + prod (`B5:65:E4:EC:DD:86:01:20:4E:13:76:39:5E:70:55:5E:28:03:C3:3E`)
- **Release keystore generated**: `android/release.keystore` (not committed)
- **Signing config added**: `release` build type signs with release keystore

### Phase 4 — Error Handling Polish
- Web download: try/catch with alert on Worker failure
- Web delete: button shows "Deleting..." + disabled during operation
- Android `downloadFile`/`deleteFile`/`generateCode`/`loadFiles`: all propagate errors to UI via Snackbar
- **Worker CORS**: `Access-Control-Allow-Origin: *` on all responses + OPTIONS preflight handler
- **Worker error handling**: try/catch around all B2 API calls, returns 500 with body + CORS headers
- **Firebase Hosting deployed**: `firebase deploy --only hosting` → 6 files live

### Phase 5 — Worker Production Hardening
- **B2 CORS configured**: bucket allows `b2_upload_file` cross-origin, fixes browser upload CORS error
- **B2 Lifecycle rule set**: `daysFromHidingToDeleting: 1` — old file versions auto-deleted after 1 day
- **Rate limiting added**: in-memory sliding-window per IP — `/api/upload-url` 10/min, `/api/download-url` 30/min, `/api/delete` 20/min, catch-all 60/min. Setup endpoints (`set-cors`, `set-lifecycle`) exempt.
- **Worker endpoints**: `GET /api/upload-url`, `GET /api/download-url`, `DELETE /api/delete`, `POST /api/set-cors`, `POST /api/set-lifecycle`

### Phase 6 — Unit Tests (Android)
- **Dependencies added**: JUnit4, Mockk, kotlinx-coroutines-test
- **AuthViewModelTest**: login/register success + error states, init with/without current user
- **DashboardViewModelTest**: loadFiles doc parsing + storage% computation, empty state, error handling, toggleSelection, generateCode (6-char validation), uploadFile delegation, deleteFile
- **Test command**: `./gradlew app:testDebugUnitTest` (requires JDK with jlink, e.g. `/home/wise/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`)

### Phase 7 — Folders (MVP) + UX Polish
- **Firestore rules**: added `/folders/{folderId}` owner-only rule
- **Folder CRUD (web)**: create via "+ Folder" button + prompt, delete resets children to root
- **Folder filter (web)**: dropdown above file list filters files by folder. Upload targets selected folder.
- **Folder chips (Android)**: chip row below storage bar, filter by tap, clear by tap again. "+" chip opens create dialog.
- **Folder name display**: shown in file rows (web badge, Android subtitle line)
- **Web drag-drop upload**: native HTML5 API, drop anywhere on page triggers upload
- **File size display**: formatted size (`3.2 MB`) shown in file rows on both platforms

### Bugfixes
- **B2 API v3 Compatibility**: authorize response URLs moved from top-level `apiUrl`/`downloadUrl` to nested `apiInfo.storageApi.apiUrl`/`apiInfo.storageApi.downloadUrl`
- **Google Sign-In API**: `GetGoogleIdTokenCredentialOption` → `GetGoogleIdOption` (renamed in credentials 1.2.2)
- **LoginScreen missing context**: added `import LocalContext` + `val context = LocalContext.current`
- **Missing FileProvider config**: created `res/xml/file_paths.xml` (needed by AndroidManifest FileProvider)

## Next Steps (Priority Order)

### 1. Folder Tree (Google Drive-like file system)
- **Nested folders**: enable `parentId` on folders for hierarchy. Breadcrumb or tree navigation.
- **Folder sidebar (web)**: collapsible tree on left pane with expand/collapse icons. Shows nested folder structure.
- **Navigation rail (Android)**: folder tree panel or bottom sheet breadcrumb for navigating hierarchy.
- **Move file to folder**: context menu or drag-drop file onto folder in sidebar to move.
- **Breadcrumb bar**: show current path (e.g., "My Files > Math > Homework") with clickable segments.
- **Empty folder state**: illustration + prompt on folder with no files.
- **Rename folder**: context menu option on folder items.

### 2. UI/UX Polish (Web + Android)
- M3 theme colors not fully applied in Compose
- Dashboard file list flat (no icons, no hierarchy)
- No loading skeletons / empty state illustrations
- Storage bar text overlaps on narrow widths
- Login screen spacing tight on small screens
- Google Sign-In button inconsistent with M3 style

### 3. Custom Domain
- Firebase Hosting custom domain instead of `vdrive-64deb.web.app`

### 4. Web design overhaul
- Match DESIGN.md spec (warm cream canvas, coral accent)
- Responsive polish for mobile browsers

### 5. CI / CD
- GitHub Actions: test on PR, deploy on merge

## What Was Tried & Failed

| Attempt | Reason Failed |
|---------|---------------|
| Firebase Storage | Requires credit card on signup |
| Cloudinary free tier | 10 MB raw file cap, not enough for 20 MB+ classroom files |
| Supabase Storage | 50 MB cap works, but only 1 GB total vs B2's 10 GB free |
| B2 public bucket | Making bucket public requires CC verification on B2 |
| Worker upload proxy (plan) | User chose simpler B2 CORS config instead |

## What Won (Current)
**Backblaze B2 (private bucket) + Cloudflare Worker** — 10 GB free, no per-file limit, CC only needed for initial verification (not recurring). Worker keeps app key server-side. Signed download URLs give access control without public bucket.
