---
version: 1.2
name: vdrive-design-system
description: Navy & Cool design system for Virtual Pendrive, a cloud USB drive for classrooms. Navy blue primary, cool light canvas, Inter throughout - clean, professional, classroom-ready.
---

## Palette

- canvas: "#f4f5f6"       (cool light gray - never pure white)
- surface-card: "#ffffff"  (white card on cool canvas)
- surface-soft: "#edeef0"  (section dividers, hover states)
- surface-dark: "#1a1f2e"  (deep navy - hero mockup, footer)
- surface-dark-soft: "#212638" (slightly lighter navy)
- primary: "#3B5C9A"       (navy blue - brand + CTAs)
- primary-active: "#2D4A7A"
- primary-disabled: "#c8d2e0"
- ink: "#1c1b1e"           (warm dark, slightly off-black)
- body: "#3d3d3a"
- muted: "#6c6a64"
- muted-soft: "#8e8b82"
- hairline: "#e0e1e3"
- on-primary: "#ffffff"
- on-dark: "#f4f5f6"
- on-dark-soft: "#a09da6"
- success: "#5db872"
- warning: "#d4a017"
- error: "#c64545"

### File type badge colors

- pdf: warm rust "#C27A5C"
- ppt: golden amber "#D4A373"
- doc: sage "#7EB89E"
- video: lavender "#9B7FC8"
- zip: slate "#6c6a64"
- default: muted "#8e8b82"

## Typography

All text uses Inter (humanist sans) at appropriate weights. No serif.
Code uses JetBrains Mono.

Fallbacks: system sans.

| Token | Size | Weight | Line Ht | Tracking | Use |
|---|---|---|---|---|---|---|
| display-xl | 48px | 500 | 1.1 | -1px | Landing h1 |
| display-lg | 36px | 500 | 1.15 | -0.5px | Section heads |
| display-md | 28px | 500 | 1.2 | -0.3px | Auth card heading |
| title-md | 18px | 500 | 1.4 | 0 | Card titles |
| title-sm | 16px | 500 | 1.4 | 0 | File name, folder name |
| body-md | 16px | 400 | 1.55 | 0 | Body text |
| body-sm | 14px | 400 | 1.55 | 0 | Secondary text |
| caption | 13px | 500 | 1.4 | 0 | Badges, timestamps |
| caption-up | 12px | 500 | 1.4 | 1.5px | File type badges |
| button | 14px | 500 | 1.0 | 0 | Buttons |
| code | 14px | 400 | 1.6 | 0 | Code display, access codes |

## Spacing

xxs: 4px, xs: 8px, sm: 12px, md: 16px, lg: 24px, xl: 32px, xxl: 48px, section: 96px

## Radius

xs: 4px, sm: 6px, md: 8px, lg: 12px, xl: 16px, pill: 9999px

## Elevation

- Flat: no shadow - body sections, nav, cards
- Hairline: 1px `hairline` border - inputs, card containers, file rows
- Surface card: `surface-card` background with border - no shadow
- Dark surface: `surface-dark` background - hero mockup, footer
- Hover: faint shadow `0 1px 4px rgba(20,20,19,0.06)` - file rows on hover

## Components

### nav-top
```
height: 56px
background: surface-card
border-bottom: 1px hairline
padding: 0 24px
display: flex, justify-between, align-center
brand: icon + "Virtual Pendrive" in 14px/600 primary
links: user email (muted), "Password" link, "Sign out" - 14px/500
```

### nav-landing
```
height: 64px
background: canvas
Same layout as nav-top
links: "Sign in" (text-link, hover primary), "Get Started" (primary button)
```

### button-primary
```
background: primary (navy)
color: on-primary
border: none
height: 40px
padding: 0 20px
radius: md (8px)
font: button
hover: primary-active
disabled: opacity 0.5, cursor not-allowed
```

### button-secondary
```
background: surface-card
color: ink
border: 1px hairline
height: 40px
padding: 0 20px
radius: md
font: button
hover: surface-soft
```

### button-ghost
```
background: transparent
color: muted
border: none
height: 34px
padding: 0 12px
radius: sm
font: 13px/500
hover: color error (for delete), or color ink (for actions)
```

### button-icon
```
background: transparent
border: none
color: muted
width: 32px, height: 32px
radius: sm
hover: surface-soft
Used for folder delete, close buttons
```

### text-input
```
background: surface-card
color: ink
border: 1px hairline
height: 40px
padding: 10px 14px
radius: md (8px)
font: body-md
focus: border-color primary, box-shadow 0 0 0 3px rgba(59,92,154,0.12)
placeholder: muted-soft
```

### access-code-input
```
Same as text-input but:
font: code (JetBrains Mono)
font-size: 22px
font-weight: 700
text-align: center
letter-spacing: 0.3em
height: 52px (larger, prominent)
text-transform: uppercase
```

### card
```
background: surface-card
border: 1px hairline
radius: lg (12px)
padding: 24px
```

### card-dashed
```
border: 2px dashed hairline
radius: lg
padding: 48px
text-align: center
Used for empty state, drag-drop zone
```

### file-row
```
display: flex, align-items: center
background: surface-card
border: 1px hairline
radius: lg (12px)
padding: 12px 16px
hover: box-shadow 0 1px 4px rgba(20,20,19,0.06)
Layout: [checkbox] [file-type-badge] [filename] [size] [delete]
gap: 12px between items
filename: truncate, ellipsis, flex:1
size: muted, text-xs, shrink-0
delete: button-ghost, color muted-soft, hover error
```

### folder-row
```
Same as file-row but:
cursor: pointer (click to navigate)
icon: folder SVG in primary (navy) color before name
name: text-ink, font-weight 500

Sub-folders rendered BEFORE file rows (Drive convention).
```

### file-type-badge
```
font: caption-up (12px/500/1.5px tracking)
text-transform: uppercase
Font color per file type palette (see above)
Can optionally have tinted background variant:
  pdf: rgba(194,122,92,0.1) bg
  ppt: rgba(212,163,115,0.1) bg
  doc: rgba(126,184,158,0.1) bg
  video: rgba(155,127,200,0.1) bg
  zip: rgba(108,106,100,0.1) bg
```

### file-checkbox
```
width: 16px
height: 16px
accent-color: primary (navy)
margin-right: 4px or left-aligned in file-row gap
```

### breadcrumb
```
display: flex, gap: 8px, align-items: center
Segments: text-sm, ink color
Separator: "›" character in muted-soft
Clickable segments: primary (navy) color, cursor pointer
Last (current) segment: ink, not clickable
```

### storage-bar
```
background: surface-card
border: 1px hairline
radius: lg
padding: 16px
Layout: label "Storage" + "X MB / 1 GB" text, side-by-side
Bar: height 6px, background hairline, radius pill
Fill: height 100%, background primary (navy), radius pill
Fill width: percentage, transition 0.3s
```

### access-code-card
```
background: surface-card
border: 1px hairline
radius: lg
padding: 24px
Title: "Classroom Access Code" 14px/600 ink
Subtitle: "Share this code. Expires in 15 minutes." body-sm muted
Code area: dark badge-style container
  background: surface-dark
  color: on-dark
  font: 22px/700 code, letter-spacing 0.3em
  padding: 12px 20px
  radius: md
Copy button: button-primary (navy bg) next to code
```

### empty-state
```
centered content in card-dashed or inline
icon/illustration: optional, 48px muted-soft tinted
message: "No files yet" in title-md primary (navy)
sub-message: body-sm muted "Upload your first lecture material."
```

### upload-label
```
When uploading: show "Uploading..." text, button disabled state
When idle: "Upload file"
button-primary style wrapper around hidden file input
```

### auth-card
```
background: surface-card
border: 1px hairline
radius: xl (16px)
top strip: 4px primary (navy)
padding: 24px
max-width: 380px
centered on page
fields stack with 14px gap
or-divider: hairline line with "or" text in surface-card background bubble
```

### divider
```
border-top: 1px hairline
Used in auth card (or-divider), section separation
When text in middle: centered label over 1px line
  label background matches parent background
  font: 12px muted
```

### error-banner
```
background: rgba(198,69,69,0.08)
color: error
padding: 8px 16px
radius: md
font-size: 13px
margin-bottom: 16px
```

### success-banner
```
background: rgba(93,184,114,0.08)
border: 1px rgba(93,184,114,0.2)
color: darker green (#166534)
padding: 12px
radius: md
font-size: 13px
```

### landing-hero
```
background: canvas
padding: section (96px) 0
Layout: 2-column flex, gap 80px
Left: heading display-xl + sub body-md + CTA buttons
Right: dark mockup card (card-dark with file list demo)
Max content: 1200px container
```

### landing-feature-card
```
background: surface-card
border: 1px hairline
radius: lg
padding: 24px
text-align: left
Top: 36px round icon in primary (navy) bg, white SVG
Title: title-sm, ink, weight 600
Body: body-sm, muted
```

### landing-cta-band
```
background: primary (navy)
radius: xl (16px)
padding: 56px 32px
text-align: center
Heading: on-primary, display-md Inter
Sub: on-primary at 0.75 opacity
Button: inverted - white bg, primary text
```

### landing-footer
```
background: surface-dark
padding: 48px 24px
Logo + name in on-dark
Body: body-sm in on-dark-soft
Text align: left
```

## Page Layouts

### Marketing (index.html)
```
1. nav-landing (64px, canvas)
2. landing-hero (cool + navy mockup, 2-col)
3. feature section (surface-soft, 3 cards)
4. CTA band (navy callout)
5. landing-footer (deep navy)
Surface alternation: cool → soft → navy callout → navy footer
```

### Dashboard
```
1. nav-top (56px, surface-card)
2. Main content area (max-width 680px, centered)
   a. Action bar: upload (primary) + share selected (secondary)
   b. Breadcrumb + folder controls
   c. Access code card (hidden until share)
   d. Storage bar (primary fill)
   e. File count text
   f. Folder rows (clickable, navy folder icon)
   g. File rows (checkbox + badge + name + size + delete)
   h. Empty state (when no files)
```

### Auth (login.html)
```
1. Centered card with navy top strip (max 380px)
2. Auth card with fields
3. Google Sign-In button below divider
4. Toggle link (text-primary)
```

### Classroom Access (access.html)
```
1. nav-top (simple, no links)
2. Code entry: centered card with navy top strip, max 380px
3. After code: file list view, max 560px
   Each file: card-hairline row with type badge + name + download arrow
```

## Do's

- Use cool canvas everywhere. White reads generic.
- Navy is the brand - buttons, links, icons, storage fill, CTA band.
- Inter only - no serif. Clean and professional.
- Dark navy surfaces for hero mockup + footer only.
- File type badge colors differentiate at a glance in dense lists.
- Breadcrumb active segment is ink, ancestors are clickable navy.
- File rows have hover elevation - subtle, not a box-shadow arms race.
- Access code input is large monospace center-aligned - makes typing codes easy.
- Sub-folders render before files (Google Drive convention).

## Don'ts

- Don't use pure white (#fff) - cool canvas is the brand.
- Don't use warm tones (coral, cream, terracotta) - that was the old palette.
- Don't use serif fonts - Inter only.
- Don't put navy on secondary actions. Ghost/outline buttons stay muted.
- Don't use the same surface for two consecutive bands - alternate.
- Don't shadow everything. Color contrast is depth.
- Don't make file rows taller than ~44px - list density matters.
- Don't nest breadcrumb text - keep inline, use "›" separator.

## Responsive

- Dashboard content: max-width 680px, padding 32px 24px on desktop.
- Mobile: padding shrinks to 16px. Breadcrumb wraps. Action bar wraps.
- Hero: 2-column → 1-column at <768px. Heading scales via clamp().
- Feature grid: 3-up → 1-up at <768px.
- Access code input: same size, container fills width on mobile.
- Nav: email text hides on mobile (show only icon/truncated brand).

## File type badge colors (code map)

Implement as CSS classes or inline style maps:
```
.pdf  → color: #C27A5C
.ppt  → color: #D4A373
.docx → color: #7EB89E
.mp4  → color: #9B7FC8
.zip  → color: #6c6a64
.def  → color: #8e8b82
```
