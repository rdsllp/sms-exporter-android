# SMS Exporter – Design

Date: 2026-11-02
Status: DRAFT, awaiting owner approval

## 1. Intent

A personal Android app for one person, used on two phones (a Samsung Galaxy A57 on
Android 16 and a Pixel). Its job is to **browse and export** text-message
conversations – SMS, MMS and RCS – to PDF, HTML, CSV and JSON. Exports are kept as
records and shared with other people as PDFs.

It is **not a messenger**. Google Messages stays the daily messaging app. This app
only reads.

### Why this shape

Research and a spike on the owner's Samsung (2026-11-02) established:

- No third-party app can send or receive RCS on Android. Only Google Messages can.
  If any other app becomes the default SMS app, RCS stops working on the phone.
- Google Messages writes SMS, MMS **and RCS** (including end-to-end-encrypted RCS
  chats, sent and received, with attachments) into the shared Telephony provider.
  A normal app holding `READ_SMS` can read all of it. Holding the SMS role exposed
  nothing extra on the Samsung.
- Public reports say some Google Messages versions hide encrypted RCS rows from
  non-default apps. That was not reproduced here, but the app keeps an optional
  "default-app mode" as a fallback.

## 2. Goals

1. Show every conversation and every message correctly, matching what Google
   Messages shows: same grouping, contact names, attachments, rich cards, received
   reactions.
2. Fast. The Samsung has ~22,800 messages across ~2,960 raw threads and must open
   and scroll without visible lag.
3. Beautiful but simple UI, Material 3, light and dark.
4. Slick animations: predictive-back swipe, swipe-to-select rows, swipe a bubble to
   reveal details, swipe between conversations.
5. Export one conversation to PDF / HTML / CSV / JSON, looking like the chat
   (bubbles, photos inline) for PDF and HTML.
6. Export several conversations at once as a ZIP.
7. Search as you type, by contact name, number, and message text. Filters for
   attachments and date range. Sort options.

## 3. Non-goals and anti-goals

- No sending, replying, notifications, or marking read.
- Never the default SMS app, not even briefly (§5.7).
- No network use at all.
- Never eligible for the SMS role: no `SMS_DELIVER`/`WAP_PUSH_DELIVER` receivers, no
  `RESPOND_VIA_MESSAGE` service, no `SENDTO` filter, and only `READ_SMS` among the SMS
  permissions (decided 2026-11-03, see §5.7).
- No Play Store distribution (sideloaded; `READ_SMS` is fine).
- Do not try to render **sent** reactions as badges (data does not exist, see §5.6).
- Do not try to read Samsung Messages' private RCS store (`content://im/chat`).
  The owner uses Google Messages on both phones.

## 4. Platform and stack (owner handed these to the builder; builder's calls)

- Kotlin, Jetpack Compose, Material 3. Existing empty project at
  `app/`, `applicationId com.example.smsexporter`, minSdk 29, compileSdk 37.
- No backend, no database of its own. Read the Telephony provider live. An in-memory
  cache of the merged conversation index is acceptable; persisting it is optional.
- PDF generation: render the same HTML template used for HTML export inside an
  offscreen `WebView` and print it to PDF through `WebView.createPrintDocumentAdapter`
  driving `PrintDocumentAdapter.onLayout/onWrite` into a file. One renderer, two
  formats, identical look. Alternative (builder's choice if WebView printing proves
  unreliable): draw with `android.graphics.pdf.PdfDocument`.
- Export output: `Downloads/SmsExporter/` via MediaStore, then the system share
  sheet is offered immediately.
- Permissions: `READ_SMS` and `READ_CONTACTS` only. No SMS-role manifest components
  (amended 2026-11-03, §5.7): the app must never be eligible for the role.

## 5. Data model and reading rules (verified on device)

### 5.1 Sources

| Provider URI | Holds |
|---|---|
| `content://sms` | SMS. `date` in **milliseconds**. `type` 1 = received, 2 = sent, 5 = failed. |
| `content://mms` | MMS **and RCS**. `date` in **seconds**. `msg_box` 1 = received, 2 = sent. |
| `content://mms/part` | Message parts: text, images, video, vCard, bot JSON. `_data` set when stored as a file. |
| `content://mms/<id>/addr` | Sender/recipients of an MMS/RCS row. `type` 137 = from, 151 = to. |
| `content://mms-sms/conversations?simple=true` | Raw threads: `_id`, `recipient_ids`, `message_count`, `date`, `read`. |
| `content://mms-sms/canonical-addresses` | `recipient_id -> address` (phone number or sender ID such as `JD-AxisBk`). |

### 5.2 Message classification

- Row in `sms` → **SMS**.
- Row in `mms` with `tr_id` starting with `proto:` → **RCS** (Google Messages).
- Any other `mms` row → **MMS**. (Rows created by `com.sec.android.easyMover` are
  Smart Switch migrations and may be either; classify by `tr_id` as above.)
- RCS **rich business message** (bank/operator card): an RCS row whose parts include
  `application/vnd.gsma.botmessage.v1.0+json` (the card payload, stored as a file)
  usually plus an image part. Render as a card: image, title/description text from
  the JSON, suggestion buttons as non-clickable chips. If the JSON cannot be parsed,
  fall back to showing the image and any text.
- Ignore `application/smil` parts.

### 5.3 Conversation merging (required)

The provider splits one Google Messages chat into several threads when the address
differs only in case or formatting. Verified: 179 such senders on the Samsung, e.g.
`JD-AXISBK` / `JD-AxisBK` / `JD-AxisBk` are three threads with 192 + 8 + 257
messages, shown as one chat by Google Messages.

Rule: a **conversation** = all threads whose normalized recipient set is equal.
Normalize each address as:

- if it contains at least 10 digits and starts with `+` or a digit: the last 10 digits
  (owner is in India; builder may use libphonenumber E.164 instead if preferred);
- otherwise: trimmed, lower-cased string.

Group threads (multiple `recipient_ids`) are merged by the set of normalized
addresses. Conversation date = max thread date; message count = sum.

### 5.4 Contact names

Resolve each normalized address through `ContactsContract.PhoneLookup` (numbers)
once per app session and cache. Alphanumeric sender IDs have no contact; show the ID
as-is. Group chats show comma-joined names. Search matches on contact name, raw
address, and message body.

### 5.5 Attachments

Read bytes through `content://mms/part/<partId>` (verified: returns full JPEG). Known
types on device: image/jpeg, png, heic, gif; video/mp4, quicktime; text/x-vcard;
audio/x-caf; application/pdf. Show images inline, video as a thumbnail with a play
glyph (opens in system player), others as a file chip with name and size.

### 5.6 Reactions (verified behaviour)

- **Received reaction**: stored as an RCS text message whose body is
  `​<emoji>​ to ' <quoted original text> '`. Parse it, find the nearest
  earlier message in the conversation whose text equals the quoted text, and show the
  emoji as a badge on that bubble. If no match, show it as its own small bubble.
- **Sent reaction**: stored as an RCS text message containing only the emoji, with no
  reference to the target. Show as a small emoji bubble. Do not guess the target.
- Exports: PDF/HTML render as above. CSV/JSON keep the raw body and add
  `kind: "reaction"` plus `reactionTo` (matched message id or null) when detected.

### 5.7 Default-app mode — dropped (owner's decision, 2026-11-03)

An earlier version of this section described an optional, export-only mode: with
airplane mode on, the app would take the SMS role for the duration of the read, hand
it back by disabling its own `SMS_DELIVER` receiver, then render. Its purpose was to
expose rows that a future Google Messages version might hide from non-default apps.

It was dropped after the Plan 3 spike on the Samsung showed that losing the role
revokes the role-granted permissions and kills the app process immediately
(logcat: "Removing package that no longer qualifies for the role" followed by
"Killing …com.example.smsexporter… permissions revoked"). "Read, release, render"
therefore cannot complete in one process; finishing the export would need a
two-phase job that survives the kill. Since Plan 1 verified that every row,
encrypted RCS included, is readable without the role, the owner chose to remove the
mode and all role eligibility from the app instead (§3).

If a future Google Messages does hide rows, the path back is a two-phase export:
read rows and attachments into the cache while holding the role, release, let
Android kill the app, and finish rendering from the cache on the next launch or in
a scheduled job. The original design is in this file's git history before
2026-11-03.

### 5.8 Messages arriving while the app holds the role — not applicable

The app never holds the role (§5.7), so no message can be delivered to it and nothing
needs storing. The earlier design (store incoming SMS/MMS into the provider while
default) is in git history with §5.7.

## 6. Screens and behaviour (owner's spec)

### 6.1 Conversation list (home)

- Search field at top; filters as you type on contact name, address, and message
  text. Body search runs over the provider with `LIKE` and may be slower; show
  results incrementally.
- Filter chips: Has attachments, Date range (start/end), message type. The message
  type chip matches on the conversation's most recent message (cheap, from the
  index), not on every message in the chat.
- Sort menu: Date newest first (default), Date oldest first, Name A–Z, Name Z–A.
- Row: avatar/initial, contact name, last message snippet, time, small type label
  (SMS / MMS / RCS) for the last message.
- Tap opens the conversation. Long-press or a short swipe right on a row enters
  selection mode and ticks the row; swipe again unticks.
- In selection mode, an export sheet slides up: choose PDF / HTML / CSV / JSON, then
  Export. Output is one ZIP with one folder per conversation (see §7).
- **Filters and search scope the export (owner's decision).** Whatever the active
  date range, attachment filter and search text leave visible in a conversation is
  exactly what gets exported. The export sheet states the active filter
  ("312 of 1,074 messages, Jan–Oct 2026") so a partial export is never a surprise.
  CSV and JSON record the filter in a header/metadata field.

### 6.2 Conversation view

- Read-only chat: received bubbles left, sent right, day separators, sender name on
  bubbles in group chats, small type + time line under each bubble, images inline,
  rich cards as cards, received reactions as badges.
- Search within the conversation.
- Drag a bubble sideways to reveal details (exact timestamp, type, sender address,
  sent/received, delivery status if present); springs back on release.
- Horizontal swipe on the screen moves to the previous/next conversation in the
  current list order.
- Swipe from the left edge goes back to the list (predictive back).
- Export button in the top bar opens the same export sheet for this one
  conversation. An in-conversation search, if active, scopes the export the same way
  list filters do.
- Loading: page messages in from the provider (e.g. 200 at a time, newest first) so a
  1,000-message chat opens instantly.

### 6.3 Export progress

A progress sheet with per-conversation progress, cancel, and on completion a
"Share" action and the file path.

## 7. Export formats

Common: file names `<ContactName or address>_<yyyy-MM-dd>.<ext>`, sanitized.
Single-conversation PDF/HTML exports are single files in `Downloads/SmsExporter/`.
Single-conversation CSV/JSON exports that have attachments are zipped automatically
(Android's share sheet cannot share a folder). Multi-select produces
`SmsExporter_<timestamp>.zip` laid out as:

```
SmsExporter_2026-11-02_2130.zip
  Priya_2026-11-02/
    Priya_2026-11-02.json          (or .csv / .pdf / .html)
    attachments/                   (CSV and JSON only)
      21562_1.jpg
  Axis Bank_2026-11-02/
    ...
```

Image fidelity: PDF and HTML embed images downsampled to ~1600 px on the long edge
(memory bound on device). CSV and JSON ship the **original** attachment bytes, so
the record is complete there.

- **HTML**: self-contained single file. Looks like the chat (bubbles, names, times,
  day separators, cards, reaction badges). Images embedded as base64 data URIs so
  the file travels alone. Videos as a poster frame plus a note.
- **PDF**: the HTML template printed to PDF (see §4), A4 portrait, page numbers in
  the footer, conversation title and export date in the header.
- **CSV**: one row per message. Columns: `id, conversation, kind (sms|mms|rcs),
  direction (sent|received), sender, recipients, timestamp_iso, body, attachments
  (semicolon-separated file names in the sibling `attachments/` folder),
  reaction_to`. UTF-8 with BOM so Excel opens it. A first comment line records the
  export date and active filter.
- **JSON**: `{ conversation: {...}, messages: [ ... ] }` with the same fields as CSV
  plus structured attachment objects (`fileName, mimeType, sizeBytes`). Attachments
  are written as real files **in a folder next to the JSON**, named
  `<messageId>_<n>.<ext>`, referenced by file name. The `conversation` object
  carries `exportedAt`, `filter` (date range, search text, attachment filter, or
  null) and `partial: true|false`.

## 8. Performance targets

- Home list visible within 1 s on the Samsung dataset (22.8k messages, 2.96k raw
  threads). Build the merged index from the threads table and canonical addresses
  only; do not scan all messages to render the list.
- Scrolling at 60 fps; images decoded off the main thread, downsampled, cached.
- Opening a conversation shows the newest page within 300 ms.

## 9. Constraints

- Must work on the Samsung (Android 16) and the Pixel (Android version TBD; verify
  provider behaviour there with the same checks as the spike before trusting it).
- No network permission in the manifest.
- Keep `minSdk 29`.

## 10. Left to the builder

- Exact visual design within Material 3 (colors, type scale, avatar colors).
- Paging and caching strategy, as long as §8 holds.
- HTML/PDF template details beyond §7.
- Phone-number normalization library choice.
- How the spike code (`app/src/main/java/com/example/smsexporter/spike/`, spike
  parts of `MainActivity.kt` and the manifest) is removed or reworked. The dump UI
  goes; the manifest role components went with §5.7 on 2026-11-03.
- Test strategy. The provider can be faked behind a repository interface for unit
  tests; on-device checks against the owner's phone are the real acceptance test.

## 11. Decisions made after the builder check

Owner decided: filters scope exports (§6.1); default-app mode and the incoming-message
store are dropped and the app is never eligible for the SMS role (§5.7, §5.8, decided
2026-11-03 after the role-loss kill was observed; this replaces the earlier
airplane-mode-gated design).

Author's calls (owner may overrule): attachment folder layout and image fidelity (§7); auto-zip of single CSV/JSON
exports with attachments (§7); message-type chip matches last message only (§6.1).
