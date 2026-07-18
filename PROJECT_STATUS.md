# Virtual Pendrive — Project Status

## What's Built

- **Web**: Vanilla HTML/CSS/JS app with Firebase Auth + Firestore. Google Drive-style UI: resizable sidebar with brand/[+ New]/storage/dark mode, list/grid view toggle, 3-dot context menu (always visible) per file, file info side panel, double-click preview (images/video/audio/PDF/text), account avatar dropdown. File upload/download/delete, access code sharing (6-char, 15 min TTL), drag-drop upload, folder tree navigation with breadcrumb.
- **Android**: Kotlin + Jetpack Compose + Hilt app. Same features as web. Google Sign-In works on device. Folder tree navigation with breadcrumb. File size display. Tap opens file in system viewer, 3-dot saves to Downloads.
- **Storage**: Backblaze B2 (private bucket `vdrive12`) via Cloudflare Worker proxy. Files stored on B2, metadata in Firestore.
- **Web deployed**: Firebase Hosting → https://vdrive-64deb.web.app
- **Worker deployed**: `b2-proxy` at `https://b2-proxy.muhaiminurrashid99.workers.dev`

## Architecture

```text
Client → Cloudflare Worker (upload auth, download proxy, delete, rate-limited) → Backblaze B2
Client → Firestore (file metadata, access codes, users, folders)
Client → Worker proxy for all downloads (B2 URL never reaches client)
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
- **Storage bar**: shows global used space (not per-folder), with formatted text (e.g. "5.2 MB / 1 GB")

### Phase 8 — Folder Tree Navigation (Google Drive-style)
- **Breadcrumb navigation**: "My Files › Folder › Subfolder" with clickable segments on both platforms
- **Nested folders**: `parentId` actively written on `createFolder()`, read on `loadContents()`
- **Navigate into folders**: click folder → shows sub-folders + files inside. Breadcrumb to go back up.
- **Sub-folders rendered first** in file list (Drive convention), clickable to navigate deeper
- **createFolder()**: writes `parentId = currentFolderId` (creates inside current folder)
- **deleteFolder()**: detaches files + sub-folders (sets folderId/parentId to null), navigates up
- **Upload targets current folder**: uses `currentFolderId` on both platforms
- **Storage bar fixed**: always computed from ALL user files, not filtered by current folder

### Bugfixes
- **B2 API v3 Compatibility**: authorize response URLs moved from top-level `apiUrl`/`downloadUrl` to nested `apiInfo.storageApi.apiUrl`/`apiInfo.storageApi.downloadUrl`
- **Google Sign-In API**: `GetGoogleIdTokenCredentialOption` → `GetGoogleIdOption` (renamed in credentials 1.2.2)
- **LoginScreen missing context**: added `import LocalContext` + `val context = LocalContext.current`
- **Missing FileProvider config**: created `res/xml/file_paths.xml` (needed by AndroidManifest FileProvider)
- **Storage bar 0% on Android**: `(data["size"] as? Long)` failed on `Double` (web-uploaded files) → fixed with `Number?.toLong()`
- **Folder filter not applying**: `Query.whereEqualTo()` returns new Query (immutable), result was discarded → fixed with `var` + reassignment
- **CI build fails — missing keystore.properties**: signing config eagerly read `keystore.properties` at config time, failed CI where file doesn't exist → guarded with `if (file(...).exists())`, only configures release signing when file present

### Phase 9 — Web Design Overhaul (Tailwind v4 + DESIGN.md)
- **DESIGN.md rewritten**: replaced Claude marketing components with dashboard-specific specs (file-row, folder-row, breadcrumb, storage-bar, access-code-card, auth-card, etc.)
- **Tailwind v4 installed** (`web/package.json` + `web/input.css`): `@theme` with 25+ color tokens from DESIGN.md (cool gray canvas, navy primary, dark navy surfaces, file-type badge colors), sans/mono font families
- **`styles.css` replaced**: hand-written CSS (238 lines) → Tailwind build output (~980 lines, purged to only used classes)
- **All 6 HTML pages rewritten**: inline `style=` attributes and custom CSS classes replaced with Tailwind utility classes:
  - `index.html` — landing hero, feature cards, CTA band, footer
  - `login.html` — auth card, form inputs, Google button
  - `dashboard.html` — nav, action bar, breadcrumb, storage bar, folder/file rows, empty state
  - `access.html` — code entry, file view list
  - `reset-password.html` — reset form, success/error banners
- **Dynamic JS class strings updated**: `loadContents()`, `handleUpload()`, `renderBreadcrumb()` element creation uses Tailwind classes
- **Checkbox selector fixed**: `.file-check` → `#fileList input[type="checkbox"]`
- **Build command**: `npm run css` (Tailwind CLI), output to `public/styles.css`
- **Deployed**: Firebase Hosting

### Phase 10 — Brand Rebrand (Navy & Cool)
- **Palette overhaul**: Claude-inspired warm cream + coral → cool gray `#f4f5f6` canvas, navy `#3B5C9A` primary, no accent color
- **Serif removed**: dropped Cormorant Garamond, Inter only throughout
- **Firefox CSS fix**: moved Google Fonts from CSS `@import` to HTML `<link>` to fix `@layer` ordering issue
- **Cache config**: `Cache-Control: no-cache` in `firebase.json` + `?v=2` query param busts stale browser cache
- **All pages redesigned**: auth cards got navy top strip, plum replaced with navy, dark surfaces changed to deep navy `#1a1f2e`
- **Android parity**: Color.kt + Theme.kt updated to match
- **DESIGN.md v1.2**: Navy & Cool design system spec

### Phase 11 — Folder Tree Polish (Google Drive-style)
- **Hardware back button (Android)**: `BackHandler` → `navigateUp()` when in subfolder
- **Folder context menu (Android)**: long-press → Rename / Delete via DropdownMenu
- **File context menu (Android)**: three-dot menu → Move to / Delete
- **Move file dialog (Android)**: folder picker dialog, updates Firestore `folderId`
- **Rename folder dialog (Android)**: prompt for new name, updates Firestore `name`
- **Folder context menu (Web)**: right-click → Rename / Delete
- **File context menu (Web)**: right-click → Move to / Delete
- **Move file function (Web)**: prompt-based folder picker, updates Firestore `folderId`
- **Rename folder function (Web)**: prompt for new name, updates Firestore `name`
- **Tests fixed**: mock Firestore folders collection, add kotlin-test dependency

### Phase 12 — Android Google Drive-style UI/UX Redesign
- **Navigation drawer**: `ModalNavigationDrawer` with Virtual Pendrive header, compact storage bar, dark mode toggle, sign out (300dp width, Google Drive-style)
- **Top bar**: `TopAppBar` with hamburger (back in subfolder), "My Files" title, view mode toggle, account icon (dropdown with email/password/sign-out)
- **Grid/list toggle**: `LazyVerticalGrid`/`LazyColumn` switch via `ViewMode` enum, toggle icon in top bar
- **File 3-dot menu**: Download, Rename, Move to, Delete (only way to access file actions — tap opens/downloads file directly)
- **File rename**: `renameFile()` in ViewModel + `RenameFileDialog`
- **Dark theme**: `darkColorScheme` in Theme.kt, `isDarkTheme` state hoisted in MainActivity, drawer toggle
- **Grid cards**: `FolderGridCard` + `FileGridCard` with compact icon+name layout
- **Drawer storage**: compact `DrawerStorageIndicator` (just bar + text, no icon/card, Google Drive-style)
- **YAGNI cuts**: Trash placeholder, SharedPreferences for view mode, View Info on folders, separate component files, real thumbnails

### Phase 13 — Web Google Drive-style UI/UX Redesign
- **Resizable sidebar**: drag handle (200-400px), width persisted in `localStorage`, css `col-resize` cursor. Brand header (folder SVG + "Virtual Pendrive"), "[+ New]" button → dropdown (Upload file / New folder), "My Files" nav item, compact storage bar, dark mode toggle.
- **Top bar redesign**: breadcrumb left (clickable segments, `›` separator), view toggle (List/Grid icon buttons), account avatar circle → dropdown (email, Change password, Sign out).
- **List view**: Drive-style columns: `[tinted file-type icon 20px] [name truncate] [size] [folder location] [⋮ 3-dot]`. 48px rows, hover highlight. Sub-folders rendered first (Drive convention).
- **Grid view**: `auto-fill` responsive columns, 48px tinted file-type icon cards + name + size + 3-dot on hover. Card click opens info panel. Folder cards navigate on click.
- **File-type SVG icons**: 7 inline SVGs per DESIGN.md palette — PDF (rust `#C27A5C`), PPT (amber), DOC (sage), Video (lava), ZIP (slate), folder (navy), generic (muted). No icon library.
- **3-dot context menu**: `data-id` + `data-type` attributes → shared `handleDotClick()`. Items: File info, Download, Rename, Open with, Delete (red, with divider). Closes on outside click.
- **File info side panel**: slides from right (360px, 0.2s transition, backdrop dim). Shows icon + name + metadata (Size, Type, Uploaded, Location) + action buttons (Download, Rename, Open with, Share, Delete). Folder info panel shows Rename.
- **Dark mode**: CSS vars redefined under `.dark` class on `<html>`, toggle in sidebar, persisted in `localStorage`. Covers canvas, surfaces, ink, hairline, muted colors.
- **Account dropdown**: top-right avatar circle (user email initial) → email display + Change password + Sign out.
- **Mobile**: hamburger toggles sidebar overlay drawer with backdrop dim, `transition: left .2s`.
- **Ponytail cuts**: no search bar, no trash/recycle bin, no sidebar nav depth, no checkbox multi-select, no right-click menu (replaced by 3-dot), no icon library (inline SVGs), no system `prefers-color-scheme` listener.
- **1 file changed**: `web/public/dashboard.html` only (801 lines). Zero new files, zero new dependencies.
- **CSS rebuilt**: `npm run css` regenerated `styles.css`.
- **Deployed**: Firebase Hosting → https://vdrive-64deb.web.app

### Bugfixes (Fixed)
- **Delete folder permission denied (Firestore rules)**: batch update on children failed if any child lacked matching `userId` → split into folder delete first (ownership), then best-effort individual child updates with per-document try/catch
- **graphify-out/ git tracking**: added `/graphify-out/` to `.gitignore`, removed from git index
- **3-dot menu quote conflict**: `handleDotClick()` used double quotes inside `onclick="..."` — HTML parser closed attribute at inner `"`, all 6 actions silently broken. Fix: single quotes in cmd strings.
- **Download opens instead of saving**: B2 responded with content-type inline, `download` attr on cross-origin `<a>` ignored. Fix: proxy through Worker with `Content-Disposition: attachment`.
- **B2 signed URL exposed in DOM**: anyone with link could access file. Fix: Worker proxy fetch, B2 URL never reaches client.

### Phase 14 — Download Proxy + Preview + UX Fixes
- **Worker `/api/download` endpoint**: `POST` handler fetches file from B2 server-side (auth token), streams to client with `Content-Disposition: attachment` + CORS headers. B2 URL never reaches client DOM. Rate-limited 30/min.
- **Web download via proxy**: `downloadFile()` POSTs to `/api/download`, gets blob response, triggers save via `URL.createObjectURL`. No direct B2 URLs.
- **Access page download via proxy**: same approach — click handler fetches from proxy, blob + anchor click, no B2 URL exposed.
- **3-dot menu always visible**: removed `display:none` + hover-only CSS on `.file-menu` and `.grid-card-menu`. Buttons always shown.
- **Double-click file preview**: `previewFile()` fetches via proxy, renders inline by type:
  - Images (jpg/png/gif/webp/svg/bmp): `<img>` in dark overlay
  - Videos: `<video>` with controls
  - Audio: `<audio>` with controls
  - PDF: embedded `<embed>` viewer
  - Text/code (40+ extensions): rendered in `<pre>`
  - Others: falls back to info panel
- **Removed "Open with"**: removed from 3-dot menu and info panel. Removed `openFile()` function. Dead code after download proxy replaced direct URL approach.
- **Removed `/api/download-url` usage from web clients**: Android migrated to `/api/download` in Phase 15.
- **Deployed**: Worker + Hosting

### Phase 15 — Android Parity Gaps Resolved
- **Download proxy**: Android switched from `GET /api/download-url` (exposed signed B2 URL) to `POST /api/download` (Worker streams blob server-side). Same proxy pattern as web. B2 URL never reaches client.
- **FileGridCard 3-dot menu**: added persistent `MoreVert` + `DropdownMenu` (was the only card missing it).
- **Download saves to device**: 3-dot "Download" saves to Downloads folder (not just open):
  - API 29+: `MediaStore.Downloads` (persists, shows system notification)
  - API 26-28: direct write to `DIRECTORY_DOWNLOADS`
  - Shows "Saved to Downloads" toast
- **Removed**: `FileDetailBottomSheet` (metadata popup on tap — was redundant with 3-dot menu + tap-to-open)
- **Zero new dependencies**: stdlib `HttpURLConnection` + `JSONObject` only
- **Changed files**: `DashboardViewModel.kt`, `DashboardScreen.kt`

### Phase 16 — CI/CD Pipeline

### Phase 17 — Android Tap-to-Open vs 3-dot Download
- **Tap opens file**: added `previewFile()` in DashboardViewModel — fetches bytes from Worker proxy, writes to `context.cacheDir`, opens with `Intent.ACTION_VIEW` via existing `FileProvider` (cache-path). No persistent save.
- **3-dot unchanged**: `downloadFile()` still saves to Downloads folder (MediaStore API 29+ / direct write older). No changes to 3-dot behavior.
- **Zero new dependencies**: uses stdlib `HttpURLConnection`, `Intent`, existing `FileProvider` config.
- **Changed files**: `DashboardViewModel.kt` (added `previewFile`), `DashboardScreen.kt` (onClick → `previewFile`)

### Phase 18 — User-Friendly Auth Error Messages
- **Android AuthViewModel**: added `authError()` helper mapping `FirebaseAuthException` error codes to user-friendly strings (e.g. "Incorrect email or password" for invalid-credential/user-not-found/wrong-password). Applied to login, register, resetPassword, signInWithGoogle catch blocks.
- **Android DashboardViewModel**: added `userMessage()` helper mapping `FirebaseFirestoreException` codes + network exceptions. Applied to all 13 catch blocks + changePassword callback.
- **Web login.html**: added `authErrorMessage()` JS function with same mapping. Applied to handleAuth, handleGoogle, handleReset catch blocks.
- **Web dashboard.html**: added `authErrorMessage()` + `userErrorMessage()` JS functions. Applied to changePassword, upload, download, delete catch blocks.
- **Web access.html**: added `userErrorMessage()` JS function. Applied to code entry Firestore catch block.
- **Web reset-password.html**: added `authErrorMessage()` JS function (with expired/invalid action code). Applied to both handleReset and sendResetEmail catch blocks.
- **All errors fallback**: unknown errors show "Something went wrong" instead of raw exception text.
- **Zero new dependencies**: stdlib only.
- **Changed files**: `AuthViewModel.kt`, `DashboardViewModel.kt`, `login.html`, `dashboard.html`, `access.html`, `reset-password.html`

### Phase 19 — Web UI Fixes
- **Avatar dropdown**: right-aligned under avatar button (`right` instead of `left` positioning), prevents clipping off-screen.
- **Changed files**: `dashboard.html`
- **GitHub repo created**: `Muhaiminurrashid/vdrive` (private)
- **Workflow file**: `.github/workflows/deploy.yml` — two jobs:
  - `test`: runs on every PR + push to `main` — Android unit tests (`./gradlew app:testDebugUnitTest`) + CSS build (`npm run css`)
  - `deploy`: runs after `test` on `main` pushes only — deploys Firebase Hosting + Cloudflare Worker
- **GitHub secrets set**: `FIREBASE_TOKEN` (CI refresh token) + `CLOUDFLARE_API_TOKEN` (Workers edit scope)
- **Worker secrets persist across deploys** — `wrangler deploy` uploads code only, existing `B2_APP_KEY_ID`/`B2_APP_KEY` stay intact
- **No Android release build in CI** — keystore stays local; CI runs unit tests only

### Phase 20 — Access Page: Preview + Remove Code Display
- **Preview overlay**: added dark overlay preview for images, PDFs, video, audio, text — same pattern as dashboard `previewFile()`. File row click triggers preview, Download button unchanged.
- **Code display removed**: the big font-mono code + Copy button + "Enter different code" + expiry note removed. Teacher writes code on board, page shows only code input → file list.
- **Dead code deleted**: `resetAccess()`, `copyAccessCode` onclick handler, `document.getElementById('displayCode')` line.
- **Download error**: uses `userErrorMessage(e)` instead of raw `alert('Download failed')`.
- **1 file changed**: `web/public/access.html`. Zero new dependencies.
- **Deployed + pushed**: Firebase Hosting + GitHub main.

### Removed Code
- **Breadcrumb delete icon**: red `X` delete button (BreadcrumbBar) removed from both platforms — use context menu instead
- **Breadcrumb + button**: small `+` in breadcrumb bar removed — use FAB instead
- **Share row removed**: generate code / share selected buttons removed from file list header (re-add later)
- **CodeBottomSheet invocation**: removed (composable kept for later re-add)
- **Right-click context menu (web)**: replaced by 3-dot click menu (Phase 13)
- **Centered max-w-[680px] layout (web)**: replaced by sidebar + flex content layout (Phase 13)
- **Top header email/password/sign-out row (web)**: replaced by account avatar dropdown (Phase 13)
- **Checkbox multi-select + Share selected button (web)**: replaced by per-file Share in info panel (Phase 13)
- **"Open with" button**: removed from 3-dot menu and info panel (Phase 14)
- **`openFile()` function**: removed — dead code after download proxy replaced direct URL approach (Phase 14)
- **Direct B2 download URL exposure**: replaced by Worker proxy download (Phase 14)
- **Download cache + intent open**: replaced by persistent save to Downloads folder (Phase 15)
- **FileDetailBottomSheet**: removed (Phase 15) — tap action now uses simple `downloadFile()` instead of metadata popup

## Next Steps

### (done) Split Tap vs 3-dot Download Behavior
- Tap opens file via cache + intent (`previewFile()`), 3-dot saves to Downloads (`downloadFile()`)

### (done) CI / CD
- GitHub Actions: test on PR, deploy on merge — **deployed**

### (done) User-Friendly Error Messages Across All Operations
- Android DashboardViewModel: added `userMessage()` helper mapping `FirebaseFirestoreException` codes (PERMISSION_DENIED → "Permission denied", UNAVAILABLE → "Service unavailable", etc.), network exceptions → "Network error. Check your connection.", "File too large" passed through. Applied to all 13 catch blocks + changePassword callback.
- Web dashboard.html: added `userErrorMessage()` JS function covering network/Firestore/B2 errors. Replaced raw `e.message` in upload, download, delete catch blocks.
- Web access.html: added `userErrorMessage()` JS function. Applied to code entry Firestore catch block.
- Web reset-password.html: added `authErrorMessage()` JS function (with expired/invalid action code). Applied to both handleReset and sendResetEmail catch blocks.
- All errors fallback to "Something went wrong" instead of raw exception text.

### (postponed) Custom Domain
- Firebase Hosting custom domain — postponed, no domain purchased.

## Future Ideas (Unprioritized)

### Search
- File/folder search bar in top bar or sidebar. Filter client-side from loaded files, or Firestore query for larger sets.

### Trash / Recycle Bin
- Soft-delete files to a `trashed` state. 30-day auto-purge. Restore from trash UI.

### Multi-file Operations
- Checkbox selection mode → bulk download (zip), bulk delete, bulk move.

### Drag-drop Reorder / Move
- Drag files to folder in sidebar or breadcrumb to move them.

### Share Improvements
- Share multiple files in one code (already works), but add share via email link, QR code generation.
- Code expiry picker (custom TTL instead of fixed 15 min).

### Student Upload
- Allow code recipients to upload files too (homework submission flow).

### Web Push Notifications
- FCM push when someone accesses your shared code.

### Activity Log
- Track file views, downloads, code accesses per file.

### Offline / PWA
- Service worker for offline file list. Cache downloaded files for offline access.

### Android
- File preview dialog (double-tap to preview images/text inline, like web)
- Image viewer zoom/pan
- Pull-to-refresh for file list
- Upload progress indicator

### Performance
- Pagination/lazy loading for large file lists (Firestore `limit` + `startAfter`).
- Virtual scrolling for 1000+ files.

### Security
- Production Firestore rules (current rules expire Aug 2026).
- Rate-limit by user UID in addition to IP.
- File upload virus scanning.

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
