# SMS Exporter – Plan 3 of 3: Remove default-app mode

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take the default-SMS-app machinery out of the app so it can never hold the SMS role, and record in the spec why the mode was dropped.

**Architecture:** Nothing new. The app keeps reading through the provider with `READ_SMS` only. The Plan 1 role stubs (`role/RoleComponents.kt`) and the manifest declarations that made the app eligible for the role are deleted; without an `SMS_DELIVER` receiver Android refuses to give the app the role at all.

**Tech Stack:** as before.

**Spec:** `docs/superpowers/specs/2026-11-02-sms-exporter-design.md` (§3, §5.7, §5.8, §11 as amended 2026-11-03)

**History:** The original Plan 3 (export-only role window with an airplane-mode gate and an incoming-message store) was abandoned on 2026-11-03. On the Samsung, losing the role revokes the role-granted permissions and kills the app process at once, so "read, release, render" cannot finish in-process. Plan 1 had already verified that every row, encrypted RCS included, is readable without the role, so the owner dropped the mode rather than rebuild export as a two-phase job. The original plan text and the Task 1 code are in git history before this rewrite.

## Global Constraints

- The app must never be able to hold the SMS role: no `SMS_DELIVER` receiver, no `WAP_PUSH_DELIVER` receiver, no `RESPOND_VIA_MESSAGE` service, no `SENDTO` activity filter, and no `RECEIVE_SMS`, `RECEIVE_MMS`, `SEND_SMS` or `RECEIVE_WAP_PUSH` permission.
- `READ_SMS` and `READ_CONTACTS` stay. The `<queries>` block for `SENDTO`/`smsto` stays: `diag/DeviceCheck.kt:40` needs it for `Telephony.Sms.getDefaultSmsPackage`.
- Commit after the task.

## Review Focus

1. The device-check report still names the default SMS app after the manifest change (the `<queries>` block must survive). (Task 1 step 4, device: report shows `com.google.android.apps.messaging`)
2. The app must be refused the role on the device. (Task 1 step 4: `cmd role add-role-holder android.app.role.SMS com.example.smsexporter` fails and the holder is unchanged)

---

### Task 1: Delete the role machinery

**Files:**
- Delete: `app/src/main/java/com/example/smsexporter/role/RoleComponents.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: existing unit suite only (`./gradlew :app:testDebugUnitTest --rerun`, expect 132 tests, 0 failures)

**Interfaces:** none produced or consumed.

- [ ] **Step 1: Confirm nothing else references the role code** — `grep -rn "role\.\|RoleManager\|SmsDeliverReceiver\|MmsDeliverReceiver\|HeadlessSmsSendService" app/src --include='*.kt' --include='*.xml'` must list only `RoleComponents.kt` and the manifest.
- [ ] **Step 2: Delete `RoleComponents.kt`** and remove its now-empty package directory.
- [ ] **Step 3: Edit the manifest** — remove the four `uses-permission` lines for `RECEIVE_SMS`, `RECEIVE_MMS`, `SEND_SMS`, `RECEIVE_WAP_PUSH`; remove the second `<intent-filter>` on `MainActivity` (`SEND`/`SENDTO` with the sms/smsto/mms/mmsto schemes); remove both `<receiver>` elements and the `<service>` element. Replace the header comment "Permissions + components needed to be eligible for the SMS role" with "Read-only access. No receiver or SENDTO filter: the app must never qualify for the SMS role." Keep `READ_SMS`, `READ_CONTACTS` and the `<queries>` block (update its comment to drop the words "every SMS app must have").
- [ ] **Step 4: Verify** — `./gradlew :app:testDebugUnitTest --rerun` passes (132 tests); `./gradlew :app:installDebug` then `adb shell pm grant com.example.smsexporter android.permission.READ_SMS` and `… READ_CONTACTS`; `adb shell dumpsys package com.example.smsexporter | grep -c "SMS_DELIVER\|WAP_PUSH_DELIVER\|RESPOND_VIA_MESSAGE"` prints `0`; `adb shell cmd connectivity airplane-mode enable`, then `adb shell cmd role add-role-holder android.app.role.SMS com.example.smsexporter` prints an error and `adb shell cmd role get-role-holders android.app.role.SMS` still prints `com.google.android.apps.messaging`; `adb shell cmd connectivity airplane-mode disable`. Paste the outputs in the report.
- [ ] **Step 5: Commit** `chore(role): remove default-sms-app eligibility`
