# Virtual Pendrive - Project Status

## What's Built

- **Web**: Vanilla HTML/CSS/JS app with Firebase Auth + Firestore. Google Drive-style UI: resizable sidebar with brand/[+ New]/storage/dark mode, list/grid view toggle, 3-dot context menu (always visible) per file, file info side panel, double-click preview (images/video/audio/PDF/text), account avatar dropdown. File upload/download/delete, access code sharing (6-char, 15 min TTL), drag-drop upload, folder tree navigation with breadcrumb. Access page: OTP-style code entry, dark mode across dashboard + access page.
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

### Phase 0 - Infrastructure
- **Worker deployed** (`wrangler deploy`) at `https://b2-proxy.muhaiminurrashid99.workers.dev`
- **Secrets set**: `B2_APP_KEY_ID`, `B2_APP_KEY`
- **`B2_PROXY_URL` updated** in all 4 files (web dashboard.html, web access.html, Android FileRepository.kt, Android DashboardViewModel.kt)

### Phase 1 - Security
- **Firestore native database created** (nam5, Spark plan - no billing)
- **Auth-gated rules deployed**:
  - `/users/{userId}` - self only
  - `/files/{fileId}` - owner read/write/create
  - `/accessCodes/{code}` - anyone read (student code entry), owner write
  - `/folders/{folderId}` - owner read/write/create
  - Catch-all deny

### Phase 2 - Storage Bar & File Size Limit
- **Web storage bar**: computed from loaded files in `loadFiles()`, displays bar + formatted size text
- **Android storage bar**: sums `size` from snapshot → `storagePercent` replaces hardcoded 0f
- **File size limit (100 MB)**: web checks in `handleUpload()`, Android throws in `FileRepository.uploadFile()`
- **Android error state**: added `error: String?` to `DashboardUiState` + SnackbarHost in screen

### Phase 3 - Android Google Sign-In
- **Dependencies added**: `credentials:1.2.2`, `credentials-play-services-auth`, `googleid:1.1.1`
- **`google-services.json` updated**: OAuth clients (Android + web) now present
- **`FirebaseService.signInWithGoogle(idToken)`**: exchanges Google ID token via `GoogleAuthProvider`
- **`AuthViewModel.signInWithGoogle(activity)`**: uses Credential Manager → `GetGoogleIdOption` → Firebase sign-in
- **SHA-1 fingerprints registered**: debug (`6B:5C:33:FB:0F:D0:8F:0E:91:5B:76:48:B7:7F:C8:7C:E0:B7:CB:A7`) + prod (`B5:65:E4:EC:DD:86:01:20:4E:13:76:39:5E:70:55:5E:28:03:C3:3E`)
- **Release keystore generated**: `android/release.keystore` (not committed)
- **Signing config added**: `release` build type signs with release keystore

### Phase 4 - Error Handling Polish
- Web download: try/catch with alert on Worker failure
- Web delete: button shows "Deleting..." + disabled during operation
- Android `downloadFile`/`deleteFile`/`generateCode`/`loadFiles`: all propagate errors to UI via Snackbar
- **Worker CORS**: `Access-Control-Allow-Origin: *` on all responses + OPTIONS preflight handler
- **Worker error handling**: try/catch around all B2 API calls, returns 500 with body + CORS headers
- **Firebase Hosting deployed**: `firebase deploy --only hosting` → 6 files live

### Phase 5 - Worker Production Hardening
- **B2 CORS configured**: bucket allows `b2_upload_file` cross-origin, fixes browser upload CORS error
- **B2 Lifecycle rule set**: `daysFromHidingToDeleting: 1` - old file versions auto-deleted after 1 day
- **Rate limiting added**: in-memory sliding-window per IP - `/api/upload-url` 10/min, `/api/download-url` 30/min, `/api/delete` 20/min, catch-all 60/min. Setup endpoints (`set-cors`, `set-lifecycle`) exempt.
- **Worker endpoints**: `GET /api/upload-url`, `GET /api/download-url`, `DELETE /api/delete`, `POST /api/set-cors`, `POST /api/set-lifecycle`

### Phase 6 - Unit Tests (Android)
- **Dependencies added**: JUnit4, Mockk, kotlinx-coroutines-test
- **AuthViewModelTest**: login/register success + error states, init with/without current user
- **DashboardViewModelTest**: loadFiles doc parsing + storage% computation, empty state, error handling, toggleSelection, generateCode (6-char validation), uploadFile delegation, deleteFile
- **Test command**: `./gradlew app:testDebugUnitTest` (requires JDK with jlink, e.g. `/home/wise/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2`)

### Phase 7 - Folders (MVP) + UX Polish
- **Firestore rules**: added `/folders/{folderId}` owner-only rule
- **Folder CRUD (web)**: create via "+ Folder" button + prompt, delete resets children to root
- **Folder filter (web)**: dropdown above file list filters files by folder. Upload targets selected folder.
- **Folder chips (Android)**: chip row below storage bar, filter by tap, clear by tap again. "+" chip opens create dialog.
- **Folder name display**: shown in file rows (web badge, Android subtitle line)
- **Web drag-drop upload**: native HTML5 API, drop anywhere on page triggers upload
- **File size display**: formatted size (`3.2 MB`) shown in file rows on both platforms
- **Storage bar**: shows global used space (not per-folder), with formatted text (e.g. "5.2 MB / 1 GB")

### Phase 8 - Folder Tree Navigation (Google Drive-style)
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
- **CI build fails - missing keystore.properties**: signing config eagerly read `keystore.properties` at config time, failed CI where file doesn't exist → guarded with `if (file(...).exists())`, only configures release signing when file present

### Phase 9 - Web Design Overhaul (Tailwind v4 + DESIGN.md)
- **DESIGN.md rewritten**: replaced Claude marketing components with dashboard-specific specs (file-row, folder-row, breadcrumb, storage-bar, access-code-card, auth-card, etc.)
- **Tailwind v4 installed** (`web/package.json` + `web/input.css`): `@theme` with 25+ color tokens from DESIGN.md (cool gray canvas, navy primary, dark navy surfaces, file-type badge colors), sans/mono font families
- **`styles.css` replaced**: hand-written CSS (238 lines) → Tailwind build output (~980 lines, purged to only used classes)
- **All 6 HTML pages rewritten**: inline `style=` attributes and custom CSS classes replaced with Tailwind utility classes:
  - `index.html` - landing hero, feature cards, CTA band, footer
  - `login.html` - auth card, form inputs, Google button
  - `dashboard.html` - nav, action bar, breadcrumb, storage bar, folder/file rows, empty state
  - `access.html` - code entry, file view list
  - `reset-password.html` - reset form, success/error banners
- **Dynamic JS class strings updated**: `loadContents()`, `handleUpload()`, `renderBreadcrumb()` element creation uses Tailwind classes
- **Checkbox selector fixed**: `.file-check` → `#fileList input[type="checkbox"]`
- **Build command**: `npm run css` (Tailwind CLI), output to `public/styles.css`
- **Deployed**: Firebase Hosting

### Phase 10 - Brand Rebrand (Navy & Cool)
- **Palette overhaul**: Claude-inspired warm cream + coral → cool gray `#f4f5f6` canvas, navy `#3B5C9A` primary, no accent color
- **Serif removed**: dropped Cormorant Garamond, Inter only throughout
- **Firefox CSS fix**: moved Google Fonts from CSS `@import` to HTML `<link>` to fix `@layer` ordering issue
- **Cache config**: `Cache-Control: no-cache` in `firebase.json` + `?v=2` query param busts stale browser cache
- **All pages redesigned**: auth cards got navy top strip, plum replaced with navy, dark surfaces changed to deep navy `#1a1f2e`
- **Android parity**: Color.kt + Theme.kt updated to match
- **DESIGN.md v1.2**: Navy & Cool design system spec

### Phase 11 - Folder Tree Polish (Google Drive-style)
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

### Phase 12 - Android Google Drive-style UI/UX Redesign
- **Navigation drawer**: `ModalNavigationDrawer` with Virtual Pendrive header, compact storage bar, dark mode toggle, sign out (300dp width, Google Drive-style)
- **Top bar**: `TopAppBar` with hamburger (back in subfolder), "My Files" title, view mode toggle, account icon (dropdown with email/password/sign-out)
- **Grid/list toggle**: `LazyVerticalGrid`/`LazyColumn` switch via `ViewMode` enum, toggle icon in top bar
- **File 3-dot menu**: Download, Rename, Move to, Delete (only way to access file actions - tap opens/downloads file directly)
- **File rename**: `renameFile()` in ViewModel + `RenameFileDialog`
- **Dark theme**: `darkColorScheme` in Theme.kt, `isDarkTheme` state hoisted in MainActivity, drawer toggle
- **Grid cards**: `FolderGridCard` + `FileGridCard` with compact icon+name layout
- **Drawer storage**: compact `DrawerStorageIndicator` (just bar + text, no icon/card, Google Drive-style)
- **YAGNI cuts**: Trash placeholder, SharedPreferences for view mode, View Info on folders, separate component files, real thumbnails

### Phase 13 - Web Google Drive-style UI/UX Redesign
- **Resizable sidebar**: drag handle (200-400px), width persisted in `localStorage`, css `col-resize` cursor. Brand header (folder SVG + "Virtual Pendrive"), "[+ New]" button → dropdown (Upload file / New folder), "My Files" nav item, compact storage bar, dark mode toggle.
- **Top bar redesign**: breadcrumb left (clickable segments, `›` separator), view toggle (List/Grid icon buttons), account avatar circle → dropdown (email, Change password, Sign out).
- **List view**: Drive-style columns: `[tinted file-type icon 20px] [name truncate] [size] [folder location] [⋮ 3-dot]`. 48px rows, hover highlight. Sub-folders rendered first (Drive convention).
- **Grid view**: `auto-fill` responsive columns, 48px tinted file-type icon cards + name + size + 3-dot on hover. Card click opens info panel. Folder cards navigate on click.
- **File-type SVG icons**: 7 inline SVGs per DESIGN.md palette - PDF (rust `#C27A5C`), PPT (amber), DOC (sage), Video (lava), ZIP (slate), folder (navy), generic (muted). No icon library.
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
- **3-dot menu quote conflict**: `handleDotClick()` used double quotes inside `onclick="..."` - HTML parser closed attribute at inner `"`, all 6 actions silently broken. Fix: single quotes in cmd strings.
- **Download opens instead of saving**: B2 responded with content-type inline, `download` attr on cross-origin `<a>` ignored. Fix: proxy through Worker with `Content-Disposition: attachment`.
- **B2 signed URL exposed in DOM**: anyone with link could access file. Fix: Worker proxy fetch, B2 URL never reaches client.

### Phase 14 - Download Proxy + Preview + UX Fixes
- **Worker `/api/download` endpoint**: `POST` handler fetches file from B2 server-side (auth token), streams to client with `Content-Disposition: attachment` + CORS headers. B2 URL never reaches client DOM. Rate-limited 30/min.
- **Web download via proxy**: `downloadFile()` POSTs to `/api/download`, gets blob response, triggers save via `URL.createObjectURL`. No direct B2 URLs.
- **Access page download via proxy**: same approach - click handler fetches from proxy, blob + anchor click, no B2 URL exposed.
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

### Phase 15 - Android Parity Gaps Resolved
- **Download proxy**: Android switched from `GET /api/download-url` (exposed signed B2 URL) to `POST /api/download` (Worker streams blob server-side). Same proxy pattern as web. B2 URL never reaches client.
- **FileGridCard 3-dot menu**: added persistent `MoreVert` + `DropdownMenu` (was the only card missing it).
- **Download saves to device**: 3-dot "Download" saves to Downloads folder (not just open):
  - API 29+: `MediaStore.Downloads` (persists, shows system notification)
  - API 26-28: direct write to `DIRECTORY_DOWNLOADS`
  - Shows "Saved to Downloads" toast
- **Removed**: `FileDetailBottomSheet` (metadata popup on tap - was redundant with 3-dot menu + tap-to-open)
- **Zero new dependencies**: stdlib `HttpURLConnection` + `JSONObject` only
- **Changed files**: `DashboardViewModel.kt`, `DashboardScreen.kt`

### Phase 16 - CI/CD Pipeline

### Phase 17 - Android Tap-to-Open vs 3-dot Download
- **Tap opens file**: added `previewFile()` in DashboardViewModel - fetches bytes from Worker proxy, writes to `context.cacheDir`, opens with `Intent.ACTION_VIEW` via existing `FileProvider` (cache-path). No persistent save.
- **3-dot unchanged**: `downloadFile()` still saves to Downloads folder (MediaStore API 29+ / direct write older). No changes to 3-dot behavior.
- **Zero new dependencies**: uses stdlib `HttpURLConnection`, `Intent`, existing `FileProvider` config.
- **Changed files**: `DashboardViewModel.kt` (added `previewFile`), `DashboardScreen.kt` (onClick → `previewFile`)

### Phase 18 - User-Friendly Auth Error Messages
- **Android AuthViewModel**: added `authError()` helper mapping `FirebaseAuthException` error codes to user-friendly strings (e.g. "Incorrect email or password" for invalid-credential/user-not-found/wrong-password). Applied to login, register, resetPassword, signInWithGoogle catch blocks.
- **Android DashboardViewModel**: added `userMessage()` helper mapping `FirebaseFirestoreException` codes + network exceptions. Applied to all 13 catch blocks + changePassword callback.
- **Web login.html**: added `authErrorMessage()` JS function with same mapping. Applied to handleAuth, handleGoogle, handleReset catch blocks.
- **Web dashboard.html**: added `authErrorMessage()` + `userErrorMessage()` JS functions. Applied to changePassword, upload, download, delete catch blocks.
- **Web access.html**: added `userErrorMessage()` JS function. Applied to code entry Firestore catch block.
- **Web reset-password.html**: added `authErrorMessage()` JS function (with expired/invalid action code). Applied to both handleReset and sendResetEmail catch blocks.
- **All errors fallback**: unknown errors show "Something went wrong" instead of raw exception text.
- **Zero new dependencies**: stdlib only.
- **Changed files**: `AuthViewModel.kt`, `DashboardViewModel.kt`, `login.html`, `dashboard.html`, `access.html`, `reset-password.html`

### Phase 19 - Web UI Fixes
- **Avatar dropdown**: right-aligned under avatar button (`right` instead of `left` positioning), prevents clipping off-screen.
- **Changed files**: `dashboard.html`
- **GitHub repo created**: `Muhaiminurrashid/vdrive` (private)
- **Workflow file**: `.github/workflows/deploy.yml` - two jobs:
  - `test`: runs on every PR + push to `main` - Android unit tests (`./gradlew app:testDebugUnitTest`) + CSS build (`npm run css`)
  - `deploy`: runs after `test` on `main` pushes only - deploys Firebase Hosting + Cloudflare Worker
- **GitHub secrets set**: `FIREBASE_TOKEN` (CI refresh token) + `CLOUDFLARE_API_TOKEN` (Workers edit scope)
- **Worker secrets persist across deploys** - `wrangler deploy` uploads code only, existing `B2_APP_KEY_ID`/`B2_APP_KEY` stay intact
- **No Android release build in CI** - keystore stays local; CI runs unit tests only

### Phase 20 - Access Page: Preview + Remove Code Display
- **Preview overlay**: added dark overlay preview for images, PDFs, video, audio, text - same pattern as dashboard `previewFile()`. File row click triggers preview, Download button unchanged.
- **Code display removed**: the big font-mono code + Copy button + "Enter different code" + expiry note removed. Teacher writes code on board, page shows only code input → file list.
- **Dead code deleted**: `resetAccess()`, `copyAccessCode` onclick handler, `document.getElementById('displayCode')` line.
- **Download error**: uses `userErrorMessage(e)` instead of raw `alert('Download failed')`.
- **1 file changed**: `web/public/access.html`. Zero new dependencies.
- **Deployed + pushed**: Firebase Hosting + GitHub main.

### Phase 21 - Client-Side Search Bar (Web)
- **Search input in `#contentHeader`**: text input with `200px` width in top bar area, between file count and code display. Placeholder "Search files...".
- **Filter logic**: `oninput` handler iterates `.file-row` and `.grid-card` elements, matches `.file-name` / `.grid-card-name` text (case-insensitive), toggles `display:none`. Folder names also searched.
- **Resets on navigation**: `$('searchInput').value = ''` at top of `loadContents()` - clears search when navigating folders or toggling view mode.
- **Client-side only**: no Firestore queries, no indexes. Filters currently loaded files only. No debounce, no search icon, no cross-folder search.
- **1 file changed**: `web/public/dashboard.html`. ~15 lines JS + 3 lines CSS + 1 line HTML. Zero new dependencies.
- **Deployed + pushed**: Firebase Hosting + GitHub main.

### Phase 22 - Upload/Download/Delete Progress (Web + Android)
- **Web upload**: replaced `fetch` for B2 POST with `XMLHttpRequest` - only way to get `upload.onprogress` events. Progress bar (4px navy fill) below breadcrumb.
- **Web download**: replaced `fetch` with `XMLHttpRequest` + `responseType: 'blob'`, same `onprogress` handler drives the same progress bar.
- **Web delete**: no progress bar (fast operation), shows "Deleting filename..." status text.
- **Web status text**: `#uploadStatus` label inside progress container shows "Uploading...", "Downloading filename...", "Deleting filename..." per operation.
- **Web helpers**: `showProgress(text)` / `hideProgress()` functions centralize show/reset/hide logic.
- **Android upload**: custom `okhttp3.RequestBody` writes 8KB chunks in `writeTo()`, fires `onProgress` lambda → `uploadProgress: Float?` state → `LinearProgressIndicator` with text label.
- **Android download**: chunked `InputStream` read (8KB) via `HttpURLConnection`, 1% throttle, reuses `uploadProgress` bar + "Downloading..." text.
- **Android delete**: status text only ("Deleting..."), no progress bar.
- **Android action labels**: `actionLabel: String?` on `DashboardUiState` drives "Uploading...", "Downloading filename...", "Deleting..." text below breadcrumb. Cleared in `finally` on all 3 operations.
- **Changed files**: `web/public/dashboard.html`, `FileRepository.kt`, `DashboardViewModel.kt`, `DashboardScreen.kt`
- **Zero new dependencies** on either platform.

### Bugfixes (Phase 24 regressions)
- **Download broken after Phase 24**: ownership check in worker `/api/download` returned 403 when Firestore query failed or `b2FileName` didn't match stored value. Web XHR handler masked real error with hardcoded `'Download service unavailable'` → user saw "Service unavailable. Try again." on every download. Android `HttpURLConnection.inputStream` threw `IOException` on non-2xx, real error body lost.
  - **Fix**: `verifyFileOwnership()` now fail-soft - returns `true` (allows download) if `FIREBASE_SERVICE_ACCOUNT` secret missing or Firestore query errors. Web XHR reads `body.error` from worker response instead of hardcoded string. Android checks `responseCode` before reading input stream, reads `errorStream` for real message.
  - **Lesson**: worker security checks should fail-soft (permit) when Firestore admin query fails - the pre-Phase 24 behavior was open access. Error handlers should never discard the actual error message.

### Phase 25 - Android Pull-to-Refresh
- **Pull-to-refresh**: pull down on file list (list or grid view) triggers `loadContents()`. Uses Material2 `pullRefresh` modifier + `PullRefreshIndicator` from `androidx.compose.material:material` (M2). Indicator shows spinner while `state.isLoading` is true, auto-dismisses when data loads.
- **Imports changed**: replaced Material3 `PullToRefreshContainer`/`rememberPullToRefreshState`/`nestedScroll` with M2 `pullRefresh`/`PullRefreshIndicator`/`rememberPullRefreshState`.
- **Dependency added**: `androidx.compose.material:material` (for pull-refresh API).
- **First attempt failed**: used M3 `rememberPullToRefreshState()` + `PullToRefreshContainer` + `LaunchedEffect(key = isRefreshing)` then `snapshotFlow { isRefreshing }` - indicator appeared but never dismissed. The M3 pull-to-refresh API in compose-bom:2024.02.00 was unreliable; `isRefreshing` state transitions didn't trigger properly. Switched to M2 API which worked on first try.
- **Lesson**: M3 pull-to-refresh (1.2.x) is buggy with `isRefreshing` state management. M2 `pullRefresh` modifier is simpler and more reliable - uses `refreshing: Boolean` + `onRefresh` callback, no manual state machine.
- **Build note**: requires `JAVA_HOME=/home/wise/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2` for assembly (system JDK missing `jlink`).
- **Changed files**: `DashboardScreen.kt`, `app/build.gradle.kts`
- **Pushed**: GitHub main.

### Phase 26 - File List Pagination + UID Rate Limit
- **Web pagination**: `loadContents()` now queries Firestore with `.limit(50).orderBy('createdAt', 'desc')` per folder, cursor-based `startAfter`. "Show more" button in list/grid views loads next page. Sub-folders still loaded from cache (`allFolders`), no extra query. Folders loaded once via `loadFolders()` with `parentId` stored.
- **Android pagination**: same pattern - `limit(50) + orderBy(desc) + startAfter` in `DashboardViewModel`. `hasMoreFiles: Boolean` in `DashboardUiState`. "Show more" button (`LoadMoreButton` composable) at bottom of `LazyColumn`/`LazyVerticalGrid`.
- **Storage bar**: moved to separate `loadStorageBar()` (queries ALL files for size sum). Called on init, upload, delete - not on every navigation. Web: `loadStorageBar()` added as standalone function. Android: `loadStorageBar()` in ViewModel, `storagePercent`/`totalStorageBytes` removed from `loadContents()`.
- **UID rate limit (Worker)**: added `checkUidRateLimit(uid, path)` - same sliding-window as IP rate limiter, keyed by `uid:${uid}:${path}:${slot}`. Applied in `/api/download` (30/min) and `/api/delete` (20/min). Cleanup handles 2000 entries, older slots pruned.
- **Composite index required**: `files` collection - `userId` ASC, `folderId` ASC, `createdAt` DESC. Defined in `firestore.indexes.json`. Must be deployed: `firebase deploy --only firestore:indexes`. Query will fail with index creation link until deployed.
- **Android upload fix**: `FileRepository.uploadFile()` now always sets `folderId` (even when `null`) - previously it omitted the field for root uploads, causing files to be invisible to paginated `whereEqualTo("folderId", null)` queries. Old Android-uploaded root files without `folderId` field need migration (run script or add field manually).
- **AGENTS.md fixed**: stale "Open read/write (dev mode, expires Aug 2026)" note → "Auth-gated per-user. Students can read accessCodes without auth (code entry flow)."
- **Virtual scrolling**: skipped - YAGNI, `LazyColumn` already recycles, pagination keeps DOM small. Add when measured.
- **Firestore rules**: already production-ready (auth-gated per Phase 1). No changes needed.
- **Virus scanning**: skipped - no free built-in, requires ClamAV/VirusTotal API (paid). Add when users upload malware.

### Phase 27 - Bug Cleanup + Deployment
- **3 test assertions fixed**: aligned expected values with `authError()`/`userMessage()` mapped output. Tests threw plain `Exception` (not `FirebaseAuthException`/`FirebaseFirestoreException`), so `authError()` returned `"Something went wrong"` - not the raw message. Changed all 3 assertions from raw strings to `"Something went wrong"`.
  - `AuthViewModelTest > login sets error on failure`: `"wrong password"` → `"Something went wrong"`
  - `AuthViewModelTest > register sets error on failure`: `"email exists"` → `"Something went wrong"`
  - `DashboardViewModelTest > loadFiles sets error on exception`: `"network error"` → `"Something went wrong"`
- **Firestore composite index deployed**: `firebase deploy --only firestore:indexes` - `userId ASC, folderId ASC, createdAt DESC` live.
- **Worker deployed**: `wrangler deploy` - UID rate limit (`ffb00c42`). Used `CLOUDFLARE_API_TOKEN` provided by user.
- **folderId migration checked**: queried all 4 existing files via Firestore REST API - none missing `folderId`. No migration needed.

### Phase 24 - Worker Firestore Auth + File Read Lockdown
- **Firestore rules**: `/files/{fileId}` read changed from `allow read: if true` to `allow read: if request.auth != null && resource.data.userId == request.auth.uid`. File metadata (including `b2FileName`) no longer publicly enumerable.
- **Worker**: added Firebase service account JWT/OAuth2 token exchange using `SubtleCrypto` (RS256) to call Firestore REST API for admin-tier queries. No additional dependencies.
- **Worker `/api/download`**: accepts optional `userId` parameter - when provided, queries Firestore to verify file ownership before streaming from B2. Returns 403 if caller doesn't own the file.
- **Worker `/api/delete`**: requires `userId` - verifies ownership before deleting from B2. Returns 403 if not owner.
- **Worker `/api/code-files`** (new): accepts `{ code }`, validates access code + expiry via Firestore admin query, returns file metadata (name, b2FileName, size) for the code's files. Used by access page instead of direct Firestore reads.
- **Web dashboard**: passes `window.currentUser?.uid` in download (`dashboard.html`) and delete request bodies.
- **Web access.html**: removed Firebase Firestore SDK entirely - `handleAccess()` now calls `/api/code-files` for code validation + file metadata lookup. Download calls `/api/download` (no userId - already validated by code).
- **Android dashboard**: passes `auth.currentUser?.uid` in `downloadFile()`, `previewFile()`, and `FileRepository.deleteFile()` request bodies.
- **Service account created**: `worker-firestore@vdrive-64deb.iam.gserviceaccount.com` with `roles/datastore.user`. Private key stored as Worker secret `FIREBASE_SERVICE_ACCOUNT`.
- **Changed files**: `workers/b2-proxy.js`, `firestore.rules`, `web/public/dashboard.html`, `web/public/access.html`, `DashboardViewModel.kt`, `FileRepository.kt`
- **Deployed**: Worker → Cloudflare. Firestore rules → Firebase. Web → Firebase Hosting.

### Phase 23 - Breadcrumb Size Increase + Prominent "Generate code" Button
- **Web breadcrumb**: `#breadcrumb .seg` `font-size:13px` → `20px` + `font-weight:500`. `.sep` chevron increased proportionally to `font-size:18px`.
- **Android breadcrumb**: `BreadcrumbBar` composable `labelMedium` → `titleLarge`, chevron icon `14.dp` → `20.dp`, vertical padding `4.dp` → `10.dp`.
- **Web share button relocated**: removed tiny icon-only `#shareFolderBtn` from topbar right (hidden by default, only in subfolders). Added navy pill `#genCodeBtn` in `#contentHeader` - always visible, share icon + "Generate code" text, calls `shareFolder()` at any level (including root). `shareFolder()` no longer requires folderId - generates code for all files at root.
- **Android share button relocated**: removed `Share` `IconButton` from `TopAppBar actions` (only showed in subfolders). Added navy pill `Button` in content header row with item count - always visible, "Generate code" label, calls `generateFolderCode()` at any level. ViewModel signature changed to accept `String?` folderId, omits field in doc when null.
- **Changed files**: `web/public/dashboard.html`, `DashboardScreen.kt`, `DashboardViewModel.kt`
- **Zero new dependencies** on either platform.
- **Deployed**: Web → Firebase Hosting. Android → builds clean.

### Phase 28 - Security Hardening: Token Rotation + Worker Auth Fixes

- **Cloudflare API token revoked and replaced**: old leaked token deleted, new token created with IP CIDR restriction (office subnet). Saved to `workers/.env` and GitHub Actions secret.
- **Worker ownership check fail-soft reverted** (Critical #3): `verifyFileOwnership()` changed from `return true` to `return false` on BOTH failure paths - Firestore query errors (Phase 24's fail-safe that enabled bypass) AND missing `FIREBASE_SERVICE_ACCOUNT` secret. Fail-closed: deny download when ownership can't be verified.
- **Upload endpoint auth added** (High #4): `/api/upload-url` now requires `userId` query param. Validates non-empty string before returning B2 upload URL. Prevents anonymous callers from uploading.
- **Upload size check added** (High #5): `/api/upload-url` reads `contentLength` query param, rejects files > 100 MB at Worker level (not just client-side).
- **Web client updated**: `dashboard.html` passes `userId=${window.currentUser.uid}&contentLength=${file.size}` to upload-url fetch.
- **Android client updated**: `FileRepository.kt` passes `userId=$userId&contentLength=${bytes.size}` to upload-url request.
- **Worker deployed**: version `b87ac72b` - all fixes live.
- **Firebase Hosting deployed**: updated `dashboard.html` live.
- **Zero new dependencies** on any platform.

#### Bugfix - Worker Firestore 403 (Service Account Missing IAM Role)
- **Symptom**: `/api/code-files` returned 403 "Missing or insufficient permissions." - broke student access page entirely.
- **Root cause**: Service account `worker-firestore@vdrive-64deb.iam.gserviceaccount.com` was created with a valid key (Phase 24) but **never granted `roles/datastore.user`** on the project. Key worked for OAuth2 token exchange, but token had zero Firestore access.
- **Fix**: Called GCP `setIamPolicy` via Cloud Resource Manager API (using Firebase session token - `muhaiminurrashid99@gmail.com` has `roles/owner`) to add `roles/datastore.user` binding for the service account. No code changes needed - Worker secret `FIREBASE_SERVICE_ACCOUNT` was always valid.
- **Lesson**: Creating a service account + key is not enough - it needs an IAM role binding too. The Phase 24 notes said "with `roles/datastore.user`" but that step was never executed. Always verify IAM bindings after creating service accounts.
- **Changed files**: None (GCP IAM only)

### Phase 30 - Access Page Redesign (Classroom Unlock)
- **OTP code entry**: 6 individual input boxes, auto-advance on keypress, backspace to prev, paste support (fills all 6 from clipboard), auto-submit on 6th char typed (no button needed). Navy border + focus ring per DESIGN.md spec.
- **Dark mode**: added `html.dark` CSS overrides (same palette as dashboard), moon SVG toggle in nav, persisted in `localStorage` (`vdriveDark` key). Grid dots background via CSS radial-gradient on canvas.
- **Visual overhaul**: centered hero layout, richer file cards (40px tinted icons, larger name, size + download button, hover lift), staggered fade-in animation (CSS nth-child delay), "Enter a different code" link in file view header.
- **Transitions**: code entry fades out + slides up on submit, file list slides in from bottom (CSS only, no JS animation lib).
- **Dead code removed**: `fileTypeColors`/`fileTypeColor()`, `#accessBtn` replaced by auto-submit.
- **1 file changed**: `web/public/access.html`. Zero new deps, zero Worker changes.
- **Deployed**: Firebase Hosting.

### Phase 31 - Dark Mode UI Polish
- **Brand text readability**: `--color-primary` overridden to `#ffffff` (white) in dark mode on both dashboard + access page - all blue text (links, breadcrumb, active nav, labels) renders white on dark surfaces.
- **Button backgrounds white**: `--color-on-primary` set to `#1c1b1e` - buttons, nav strip, storage bar fill become white background with dark text. Blue buttons eliminated in dark mode.
- **Folder icons**: replaced outline SVG + colored bg div with filled SVG using `fill:var(--color-primary)`. Folder shape follows primary color - navy in light, white in dark. No background div, icon size bumped (list 14→18px, grid 26→36px).
- **Files changed**: `web/public/dashboard.html`, `web/public/access.html`
- **Deployed**: Firebase Hosting.

### Phase 32 - User Doc Creation on Signup
- **Problem**: Worker ownership checks failed-closed because `users/{userId}` docs never existed. Data model specified them but no signup/login code wrote them.
- **Fix**: `ensureUserDoc()` helper on both platforms — checks doc existence, writes `{ email, displayName, createdAt: serverTimestamp }` only on first auth.
- **Web** (`login.html`): added Firestore SDK init + `ensureUserDoc()` called after email register and Google sign-in.
- **Android** (`FirebaseService.kt`): added `ensureUserDoc()` method + `FieldValue` import, called from `signUp()` and `signInWithGoogle()`.
- **Idempotent**: existing users logging in skip the write (get().exists check).
- **Bugfix**: missing `firebase-firestore-compat.js` import caused "Something went wrong" on Google sign-in (firestore was undefined). Added script + `firebase.firestore()` init.
- **Zero new dependencies** on either platform.
- **Files changed**: `web/public/login.html`, `FirebaseService.kt`
- **Pushed**: GitHub main (`b09ea3a`).

### Phase 33 - CORS Restriction (Replace `*` with Known Origins)
- **Problem**: Worker returned `Access-Control-Allow-Origin: *` on all endpoints — any website could make requests to the proxy.
- **Fix**: Replaced static `corsHeaders` with `corsOrigin(env)` / `corsHeaders(env)` functions that read `ALLOWED_ORIGINS` env var.
- **Default allowlist**: `https://vdrive-64deb.web.app` (Firebase Hosting). Configurable via Worker var.
- **All endpoints covered**: `json()` helper, `handleDownload` attachment response, `handleDelete`, setup endpoints, OPTIONS preflight — all use `corsHeaders(env)`.
- **Android**: native HTTP — no CORS enforcement, no changes needed.
- **New Worker var**: `ALLOWED_ORIGINS` added to `workers/.env` + set via `wrangler deploy --var`.
- **1 file changed**: `workers/b2-proxy.js`. Zero new dependencies.
- **Deployed**: Worker version `69b7e976`.

### Phase 34 - Admin Subscription Panel
- **Admin page** (`/admin.html`): filter tabs (Pending/Active/Rejected/All), user email lookup (fetched from `/users` collection), Approve (sets `status:active` + 30-day `expiresAt`) and Reject (`status:rejected`) buttons
- **Sidebar**: gear icon "Admin Panel" nav item shown only when admin UID matches Worker config
- **ADMIN_UID** moved from hardcoded JS to Worker env var (`workers/.env`), exposed via `GET /api/config` endpoint — no UIDs in client source
- **Firestore rules**: admin UID bypass on `subscriptions` (read all + update) + `users` (read all)
- **Fix**: after approve/reject, updates local state instead of re-fetching (avoids Firestore SDK cache staleness)
- **Zero new dependencies** on any platform
- **4 files changed**: `firestore.rules`, `workers/b2-proxy.js`, `web/public/dashboard.html`, `web/public/admin.html`
- **Deployed**: Worker + Firestore rules + Firebase Hosting

### Phase 35 - MPA Info Pages + Bugfixes (Pull-to-Refresh / Duplicate Folder Push)

- **Info pages created**: `privacy.html`, `terms.html`, `about.html`, `contact.html`, `404.html` — vanilla HTML with consistent nav + expanded footer. All pages cross-link in footer (Privacy · Terms · About · Contact).
- **Footer expanded**: `index.html` footer now has row: brand left, info links right. Same footer copied to all info pages + 404 page.
- **Contact email**: `muhaiminurrashid99@gmail.com` on contact page + privacy page.
- **Duplicate breadcrumb push fix (web + Android)**: tapping a folder 3× appended 3 copies to `folderPath` → breadcrumb showed "My Files › test › test › test". Fixed by guard: skip push if last folderPath entry already matches clicked folder id.
- **Pull-to-refresh showing during non-pull loads (Android)**: `rememberPullRefreshState` used `state.isLoading` as `refreshing` param — indicator showed during initial load, folder navigation, and upload. Fix: added local `isRefreshing` composable state that only flips true on pull gesture. `isRefreshing` reset when `state.isLoading` transitions back to false.
  - **First attempt (M3 PullToRefreshContainer)**: unreliable state transitions, indicator never dismissed. Same bug as Phase 25 note.
  - **Second attempt (M2 pullRefresh modifier)**: worked on first try with local `isRefreshing` flag — more predictable than M3 API.
- **Zero new dependencies** on any platform.
- **11 files changed**: `DashboardScreen.kt`, `DashboardViewModel.kt`, `dashboard.html`, `index.html`, `styles.css`, `404.html`, `privacy.html`, `terms.html`, `about.html`, `contact.html`
- **Deployed**: Firebase Hosting.
- **Pushed**: GitHub main.

### Phase 36 - Access Code Brute-Force Rate Limit

- **Per-code failed-attempt rate limit**: `checkCodeFailedRateLimit(code)` in Worker — same in-memory sliding window as IP/UID rate limiters, keyed by `cfail:${code.toUpperCase()}:${slot}`. Limits invalid/expired code lookups to 10/min per code.
- **Successful lookups unlimited**: 50 students all entering correct code pass through with no limit.
- **Defense in depth**: per-code rate closes targeted guessing against a known code (e.g. partial code from board). IP rate limit (30/min) still covers broad code spraying across different codes.
- **Ponytail**: reuses existing `rateMap` + cleanup. No new data structures. No client changes — Worker-only enforcement.
- **1 file changed**: `workers/b2-proxy.js` (+19 lines)
- **Deployed**: Worker version `9d3608f2`. Firebase Hosting.
- **Pushed**: GitHub main (`4a08308`).

## What Went Wrong

1. **IP restriction mismatch**: First deploy failed because new token allowed a specific IP but deploy server hit from a different IP in the same subnet. Fixed by using subnet CIDR instead of single IP.
2. **User existence check broke uploads**: Added `firestoreGet(env, 'users/${userId}')` to verify uploader exists. Crashed every upload because **no `/users/{userId}` docs are created anywhere in the app** - the data model specifies it but no signup/login code writes it. Removed the check, now validates userId is non-empty string only. **Fixed in Phase 32** — user docs now created on signup.
3. **Hosting redeploy required**: Worker fix alone wasn't enough - old `dashboard.html` (without userId/contentLength params) was cached on Firebase Hosting. Had to `firebase deploy --only hosting` to push updated client code.
4. **Pull-to-refresh M3 API unreliable**: tried `rememberPullToRefreshState()` + `PullToRefreshContainer` from Material3 (`compose-bom:2024.02.00`) — `isRefreshing` state transitions didn't trigger properly, indicator never dismissed after loading completed. M2 `pullRefresh` modifier + local `isRefreshing` flag worked reliably.

### Lesson

- **Never assume a collection exists because it's in the data model**. `/users/{userId}` was spec'd in Phase 1 but never populated. Any future check against it will silently fail. Create user docs on signup, or don't write code that depends on them.
- **Fail-closed is safer than fail-soft for security checks**, even if it temporarily breaks functionality. The Phase 24 "fix" that introduced fail-soft was the wrong lesson - it traded security for availability when the correct fix was fixing the Firestore query, not bypassing the check.
- **Firebase Hosting caches old HTML** - Worker and Hosting deploys are independent. After changing client-side API calls, always redeploy hosting too.

### What's Next

- [x] Create `/users/{userId}` doc on signup (both web + Android) - enables proper user existence verification for all endpoints
- [x] Restrict `CORS: *` to known origins - replaced with `ALLOWED_ORIGINS` env var (default: Firebase Hosting URL)
- [x] **Admin subscription panel**: list pending subscriptions, approve (set `status:active` + `expiresAt`) or reject, view history. Web-only admin page with filter tabs. Admin UID set via Worker env var.
- [x] MPA info pages: Privacy Policy, Terms & Conditions, About, Contact, 404
- [ ] Pagination on admin panel (current: loads all users/subscriptions at once)
- [ ] MIME type validation on upload
- [ ] Audit all secrets stored in CI/GitHub - ensure no tokens leak through workflow logs or env
- [ ] Access code expiry picker: 5/15/30/60 min TTL
- [ ] QR code for codes: scan -> open access page
- [ ] Android file preview: double-tap inline preview
- [ ] SEO meta tags on all MPA pages

### Removed Code
- **Breadcrumb delete icon**: red `X` delete button (BreadcrumbBar) removed from both platforms - use context menu instead
- **Breadcrumb + button**: small `+` in breadcrumb bar removed - use FAB instead
- **Share row removed**: generate code / share selected buttons removed from file list header (re-add later)
- **CodeBottomSheet invocation**: removed (composable kept for later re-add)
- **Right-click context menu (web)**: replaced by 3-dot click menu (Phase 13)
- **Centered max-w-[680px] layout (web)**: replaced by sidebar + flex content layout (Phase 13)
- **Top header email/password/sign-out row (web)**: replaced by account avatar dropdown (Phase 13)
- **Checkbox multi-select + Share selected button (web)**: replaced by per-file Share in info panel (Phase 13)
- **"Open with" button**: removed from 3-dot menu and info panel (Phase 14)
- **`openFile()` function**: removed - dead code after download proxy replaced direct URL approach (Phase 14)
- **Direct B2 download URL exposure**: replaced by Worker proxy download (Phase 14)
- **Download cache + intent open**: replaced by persistent save to Downloads folder (Phase 15)
- **FileDetailBottomSheet**: removed (Phase 15) - tap action now uses simple `downloadFile()` instead of metadata popup

## Active Security Vulnerabilities

### FIXED in Phase 28

| # | Vulnerability | Fix |
|---|--------------|------|
| 1 | **Cloudflare API token exposed in plain text** | Token revoked, new one created with IP CIDR restriction (office subnet). |
| 3 | **Worker download ownership check fail-soft** | Changed to fail-closed - returns `false` on both Firestore error and missing secret. |
| 4 | **Worker has no auth on `/api/upload-url`** | Now requires `userId` query param, validates non-empty string. |
| 5 | **No upload size check in Worker** | Reads `contentLength` query param, rejects > 100 MB at Worker level. |

### FIXED in Phase 30/31

| # | Vulnerability | Fix |
|---|--------------|------|
| 2 | **Firebase service account private key on disk** (`service-account.json`) | Deleted from disk. Key never committed. Service account valid via Worker secret only. |

### OPEN - HIGH

| # | Vulnerability | Impact | Fix |
|---|--------------|--------|-----|
| 6 | **GitHub secrets `FIREBASE_TOKEN` + `CLOUDFLARE_API_TOKEN` in CI** | If CI pipeline is compromised, both tokens readable from workflow logs or env. | Restrict token scopes to minimum, use OIDC if available. |

### FIXED in Phase 33

| # | Vulnerability | Fix |
|---|--------------|------|
| 9 | **`CORS: *` on Worker** | Replaced with `ALLOWED_ORIGINS` env var — restricted to `https://vdrive-64deb.web.app`. |

### FIXED in Phase 36

| # | Vulnerability | Fix |
|---|--------------|------|
| 7 | **Access code brute-force** (no rate limit on code entry) | Per-code failed-attempt rate limit (10/min) on `/api/code-files`, plus existing IP rate limit (30/min). |

### OPEN - MEDIUM

| # | Vulnerability | Impact | Fix |
|---|--------------|--------|-----|
| 8 | **No file type validation on upload** | Any file type accepted (exe, html, js). If served from B2 with permissive content-type, could be used for malware delivery. | Check MIME type on upload, reject executable types. Serve downloads with `Content-Disposition: attachment`. |

## Fix ASAP (Step by Step) - DONE

1. ✅ **Cloudflare token revoked** (deleted), new one created with IP CIDR restriction (office subnet). Saved to `workers/.env` + GitHub Actions secret.
2. ✅ **Audit log checked** - no suspicious activity found.
3. ✅ **Worker ownership check** fail-soft → fail-closed on both error paths.
4. ✅ **Upload endpoint** now requires `userId` + enforces `contentLength` ≤ 100 MB.
5. ✅ **Web + Android clients** pass `userId` + file size to upload endpoint.
6. ✅ **`service-account.json` on disk** - deleted from disk. Stored as Worker secret only.
7. ✅ **Worker Firestore 403** - service account lacked `roles/datastore.user`. Granted via `setIamPolicy`. No code change needed.

### Phase 29 - bKash Subscription (Manual Payment) - DONE

- **Data model**: `/subscriptions/{userId}` doc — `plan: "premium"`, `status: "pending"|"active"`, `txId`, `txAmount: 99`, `userId`, `createdAt`
- **Firestore rules**: `subscriptions/{userId}` — user read self, create self (admin verifies via Firebase Console)
- **Worker**: `handleGetUploadUrl` reads subscription doc via `firestoreGet()`, caps at 500 MB for active premium, 100 MB for free. Fail-soft to free on read error.
- **Web dashboard**:
  - Sidebar: plan badge ("Free"/"Premium") + "Upgrade" link below storage bar
  - Subscribe modal: click-to-copy bKash number `01605091313`, single TX ID input, writes `status:"pending"` doc
  - `loadStorageBar()`: fetches subscription + files, adjusts cap display (10 GB premium / 1 GB free)
  - `handleUpload()`: checks cached `window.currentSub` before size check
- **Android**:
  - `DrawerStorageIndicator`: accepts `isPremium` param, shows "10 GB" cap
  - Drawer: plan badge + "Upgrade" TextButton → `SubscribeDialog`
  - `SubscribeDialog`: copy button for bKash number, single TX ID field, calls `submitSubscription(txId)`
  - `DashboardViewModel`: `loadStorageBar()` reads sub doc, `uploadFile()` reads sub doc before upload, `submitSubscription()` writes pending doc with `txAmount: 99`
  - `FileRepository.uploadFile()`: accepts `isPremium` param, uses 500 MB cap
- **Pricing**: 99 BDT/month (hardcoded)
- **Admin flow**: user submits TX → `status:"pending"` → admin checks bKash app → sets `status:"active"` + `expiresAt` Timestamp in Firebase Console → next upload/refresh picks up premium tier
- **Zero new dependencies** on any platform
- **7 files changed**: `firestore.rules`, `workers/b2-proxy.js`, `web/public/dashboard.html`, `FileRepository.kt`, `DashboardViewModel.kt`, `DashboardScreen.kt`, `DashboardViewModelTest.kt`
- **Deployed**: Worker (`6c1c2e6a`), Firestore rules, Firebase Hosting

### Phase 30 - Access Page Redesign (Classroom Unlock) - DONE

See completed section above.

## What Was Tried & Failed

| Attempt | Reason Failed |
|---------|---------------|
| Firebase Storage | Requires credit card on signup |
| Cloudinary free tier | 10 MB raw file cap, not enough for 20 MB+ classroom files |
| Supabase Storage | 50 MB cap works, but only 1 GB total vs B2's 10 GB free |
| B2 public bucket | Making bucket public requires CC verification on B2 |
| Worker upload proxy (plan) | User chose simpler B2 CORS config instead |

## What Won (Current)
**Backblaze B2 (private bucket) + Cloudflare Worker** - 10 GB free, no per-file limit, CC only needed for initial verification (not recurring). Worker keeps app key server-side. Signed download URLs give access control without public bucket.
