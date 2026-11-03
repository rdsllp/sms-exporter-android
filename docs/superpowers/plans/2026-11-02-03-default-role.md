# SMS Exporter – Plan 3 of 3: Default-app mode (fallback) and incoming-message safety

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An optional, export-only "default-app mode" that takes the SMS role for the duration of the read, hands it back automatically, stores any SMS/MMS that arrive in the window, and recovers safely after a crash.

**Architecture:** `role/DefaultSmsRole` wraps `RoleManager` plus the self-disable trick (verified 2026-11-02: disabling the app's own `SMS_DELIVER` receiver makes Android remove the role and hand it to Google Messages). `role/IncomingSmsStore` and `role/IncomingMmsStore` write arriving messages into the provider while the role is held. `ExportCoordinator` gains a `withRole` step that reads all rows and attachment bytes to temp files before releasing. A settings toggle and a hint surface the mode.

**Tech Stack:** as before, plus `android.app.role.RoleManager`, `android.telephony.SmsManager`, `android.provider.Telephony`.

**Spec:** `docs/superpowers/specs/2026-11-02-sms-exporter-design.md` (§5.7, §5.8)

## Global Constraints

- The app must never remain default: every code path that acquires the role ends in `release()`, including crash recovery on next launch.
- Requesting the role requires the user's tap on the system dialog; the app never pretends otherwise.
- Default-app mode is export-only; browsing never requests the role.
- No `INTERNET` permission. MMS download goes through `SmsManager.downloadMultimediaMessage`, which does not need it.
- Airplane mode is required while the role is held (spec §5.7). The app never toggles it (it cannot); it reads `Settings.Global.AIRPLANE_MODE_ON`, listens to `Intent.ACTION_AIRPLANE_MODE_CHANGED`, and opens `Settings.ACTION_AIRPLANE_MODE_SETTINGS` for the user. If airplane mode goes off while the role is held, the export aborts and the role is released before anything else.
- Commit after every task.

## Review Focus

1. A multipart SMS (two or more PDUs) arriving in the window must be stored as one message, in order. (Task 2 test `multipartSmsIsConcatenated`)
2. An SMS from a sender with no existing thread must create a thread and appear in the list after release. (Task 2 instrumented `newSenderCreatesThread`)
3. The user denies the role dialog: the export must continue in normal mode and say so, not fail. (Task 3 test `deniedRoleFallsBackToNormalRead`)
4. The app is killed while holding the role: next launch releases it before showing any UI. (Task 1 instrumented `recoveryReleasesHeldRole`)
5. Re-enabling the receiver after release must not re-acquire the role. (Task 1 instrumented `reenableDoesNotReacquire`, asserts holder is Google Messages)
6. Airplane mode switched off while the role is held: the export stops, the role is released, the partial output is deleted, and the user sees the cancellation message. (Task 3 test `airplaneOffAbortsAndReleases`)

---

### Task 1: `DefaultSmsRole` and `AirplaneMode`

**Files:**
- Create: `app/src/main/java/com/example/smsexporter/role/DefaultSmsRole.kt`
- Create: `app/src/main/java/com/example/smsexporter/role/AirplaneMode.kt`
- Modify: `SmsExporterApp.kt` (call `recoverIfHeld()` in `onCreate`)
- Test: instrumented `app/src/androidTest/java/com/example/smsexporter/role/DefaultSmsRoleTest.kt` (uses `adb shell cmd role add-role-holder` through `UiAutomation.executeShellCommand` to grant without the dialog)

**Interfaces:**

```kotlin
class DefaultSmsRole(private val context: Context) {
    fun isHeld(): Boolean                                   // RoleManager.isRoleHeld(ROLE_SMS)
    fun requestIntent(): Intent                             // RoleManager.createRequestRoleIntent(ROLE_SMS)
    fun release()                                           // disable SmsDeliverReceiver component, wait until !isHeld() (poll ≤ 5 s), re-enable component
    fun recoverIfHeld() { if (isHeld()) release() }
    fun currentHolder(): String?                            // Telephony.Sms.getDefaultSmsPackage (needs <queries> for the SMS intent; add to manifest)
}
class AirplaneMode(private val context: Context) {
    fun isOn(): Boolean                                     // Settings.Global.getInt(cr, AIRPLANE_MODE_ON, 0) == 1
    fun changes(): Flow<Boolean>                            // callbackFlow over a BroadcastReceiver for Intent.ACTION_AIRPLANE_MODE_CHANGED (extra "state"), emitting isOn() first
    fun settingsIntent(): Intent                            // Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
}
```

Test (unit, `AirplaneModeTest`): `changesEmitsCurrentStateFirst` using an injected `(Context) -> Boolean` reader and a fake broadcast hook, or an instrumented check `isOnReflectsSetting` that reads the real setting (do not change it; `adb shell settings put global airplane_mode_on` plus the `am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true` is allowed in the instrumented test and must be reverted in a `finally`).

- [ ] **Step 1: Instrumented tests** `releaseHandsRoleBack` (grant via shell, `release()`, assert `!isHeld()` and `currentHolder() == "com.google.android.apps.messaging"`), `reenableDoesNotReacquire` (after release, receiver component enabled state is ENABLED/DEFAULT and holder is still Google Messages), `recoveryReleasesHeldRole` (grant via shell, call `recoverIfHeld()`, assert released).
- [ ] **Step 2: FAIL** — [ ] **Step 3: Implement** — [ ] **Step 4: PASS** (`./gradlew :app:connectedDebugAndroidTest --tests "*DefaultSmsRoleTest"`)
- [ ] **Step 5: Commit** `feat(role): acquire/release default sms role`

---

### Task 2: Store incoming SMS and MMS while holding the role

**Files:**
- Modify: `app/src/main/java/com/example/smsexporter/role/RoleComponents.kt` → split into `SmsDeliverReceiver.kt`, `MmsDeliverReceiver.kt`, `HeadlessSmsSendService.kt`
- Create: `app/src/main/java/com/example/smsexporter/role/IncomingSmsStore.kt`
- Create: `app/src/main/java/com/example/smsexporter/role/IncomingMmsStore.kt`
- Test: `app/src/test/java/com/example/smsexporter/role/IncomingSmsStoreTest.kt`; instrumented `IncomingSmsStoreDeviceTest.kt`

**Interfaces:**

```kotlin
data class IncomingSms(val address: String, val body: String, val timestampMs: Long, val subId: Int)
object IncomingSmsStore {
    fun fromIntent(intent: Intent): IncomingSms?             // Telephony.Sms.Intents.getMessagesFromIntent; concatenates bodies of all PDUs in order; address from first
    fun insert(contentResolver: ContentResolver, sms: IncomingSms): Uri?   // content://sms with type=1, read=0, seen=0, thread_id = Telephony.Threads.getOrCreateThreadId(ctx, address)
}
class IncomingMmsStore(context) {
    fun onWapPush(intent: Intent)                            // parse notification PDU (content location, transaction id, from) and call SmsManager.downloadMultimediaMessage(contentLocation, tempFileUri, null, pendingIntent)
    // download result broadcast → MmsDownloadReceiver inserts the PDU through Telephony.Mms via PduPersister-equivalent: builder may vendor the minimal PDU parser from AOSP (com.android.mms.pdu) or use a small library; if parsing fails, insert a notification-indication row so Google Messages can retry
}
```

`SmsDeliverReceiver.onReceive` → `goAsync()`, `fromIntent`, `insert`. `MmsDeliverReceiver.onReceive` → `IncomingMmsStore.onWapPush`.

- [ ] **Step 1: Unit tests** `multipartSmsIsConcatenated` (build two `SmsMessage` PDUs via `SmsMessage.createFromPdu` fixtures, or test `fromIntent` through a small `PduSource` abstraction so the test can feed plain parts), `addressTakenFromFirstPart`.
- [ ] **Step 2: FAIL** — [ ] **Step 3: Implement SMS path** — [ ] **Step 4: PASS**
- [ ] **Step 5: Instrumented** `newSenderCreatesThread`: grant role via shell, `insert(IncomingSms("+15550001234", "test", now, 1))`, assert a thread exists with that address and the sms row has `type=1`, then delete the row and release the role. (Only runs when `isHeld()`; otherwise skipped with `assumeTrue`.)
- [ ] **Step 6: Implement MMS path**; device check: with the app holding the role and an MMS sent from another phone, the image appears in Google Messages after release. If no second phone is available, note it in the report and leave the MMS path marked "untested on device".
- [ ] **Step 7: Commit** `feat(role): store incoming sms/mms while default`

---

### Task 3: Export integration and UI

**Files:**
- Modify: `app/src/main/java/com/example/smsexporter/export/ExportCoordinator.kt` (new parameter `role: DefaultSmsRole?`, request field `useDefaultMode: Boolean`)
- Modify: `app/src/main/java/com/example/smsexporter/export/ExportModels.kt` (`ExportRequest.useDefaultMode`, `ExportProgress.NeedsAirplaneMode(settingsIntent)`, `ExportProgress.NeedsRole(intent)`, `ExportProgress.Running.phase: String`, `ExportProgress.Cancelled(reason: String)`)
- Create: `app/src/main/java/com/example/smsexporter/ui/settings/SettingsScreen.kt` (toggle "Default-app mode for exports" with the explanation text below; default off; stored in `DataStore` preferences)
- Modify: `ExportViewModel.kt` (launch role intent on `NeedsRole`; on result call back into the coordinator; on denial continue in normal mode and show "Exported without default-app mode")
- Modify: `ConversationListViewModel.kt` + `ConversationRow.kt` (hint badge when `conversation.messageCount > repo.count(conversation, MessageFilter())`, tooltip "Some messages may be hidden; try default-app mode")
- Test: `ExportCoordinatorTest` additions

Explanation copy for the toggle: *"Takes the default SMS app role only while reading, then hands it back to Google Messages automatically. You will be asked to turn on airplane mode first so nothing arrives in between; keep it on until the export finishes. RCS chats pause until Google Messages is default again."*

Airplane-mode gate copy (sheet shown by `NeedsAirplaneMode`): title *"Turn on airplane mode"*, body *"While this app is temporarily the default SMS app, no message should arrive. Turn on airplane mode, then come back here; the export continues automatically."*, button *"Open airplane mode settings"*. Abort message (`Cancelled`): *"Airplane mode was switched off, so the export was cancelled and Google Messages is the default again. Keep airplane mode on until the export finishes, then try again."* Completion note: *"Done. You can turn airplane mode off now."*

Coordinator flow with `useDefaultMode`: (1) if `!airplane.isOn()`, emit `NeedsAirplaneMode(airplane.settingsIntent())` and suspend until `airplane.changes()` emits true; (2) emit `NeedsRole(role.requestIntent())` and suspend until the UI reports the result; if granted: start a watcher coroutine on `airplane.changes()`; phase "reading" → load all messages for all conversations and copy every attachment to `cacheDir/export-<ts>/`; if the watcher sees `false` at any time before release: cancel the reading job, `role.release()`, delete `cacheDir/export-<ts>/`, emit `Cancelled(<abort message>)` and stop; otherwise `role.release()` in a `finally`, stop the watcher; phase "rendering" → proceed as Plan 2 using the cached bytes; `Done.note = "Done. You can turn airplane mode off now."`. If the role is denied: proceed as Plan 2 directly and emit `Done` with `note = "Exported without default-app mode"` (add `note: String?` to `Done`).

- [ ] **Step 1: Failing tests** `deniedRoleFallsBackToNormalRead`, `roleIsReleasedEvenWhenRenderingFails` (fake role records `release()` call), `readingHappensBeforeRelease` (ordering of fake calls), `airplaneOffGatesBeforeRoleRequest` (fake airplane off → `NeedsAirplaneMode` emitted, no role request until the fake flow emits true), `airplaneOffAbortsAndReleases` (fake flow emits false mid-reading → `release()` called, temp dir deleted, `Cancelled` emitted, no `Done`).
- [ ] **Step 2: FAIL** — [ ] **Step 3: Implement** — [ ] **Step 4: PASS**
- [ ] **Step 5: Device check**: toggle on, export the lock-icon chat as PDF with airplane mode off → the gate sheet appears; turn airplane mode on (quick settings) → the system role dialog appears; tap Set as default; verify with `adb shell cmd role get-role-holders android.app.role.SMS` during and after (returns to Google Messages within 5 s of the read phase ending); PDF content identical to a normal-mode export. Second run: turn airplane mode off during the read phase → the export cancels, the role holder is Google Messages within 5 s, no file in Downloads, the abort message is shown.
- [ ] **Step 6: Commit** `feat: default-app mode for exports`
