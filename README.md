# Apni Dukan – backend

All the backend functionality of Apni Dukan: Spring Boot 4.1 · Java 21 · Neon Postgres · Flyway · Cloudflare R2 · Redis Cloud

Design: see the "Apni Dukan – Low-Level Design (Shop App MVP)" doc.

## What's built so far

| Module | Status |
| --- | --- |
| `common` | Error format, security (JWT check, CORS), current user/shop, clock |
| `auth` | Sign-in with phone OTP, tokens (refresh, sign-out), email verification, account recovery; admin roles (one super admin, admins) and OTP test mode |
| `files` | Upload photos and PDFs to R2, duplicate detection, 5-minute signed links, delete (Neon + R2) |
| `shops` | Registration (details, GPS pin, GSTIN/Udyam, kirana or salon, owner and shop photos, Submit for visit), field-team pages (dashboard, lists, what changed since approval, decisions with a checklist and notes, timeline), My shop, owner/helper/barber roles, history of every change |
| `salon` | Salon setup (services, barbers with optional sign-in and photo, weekly hours, booking settings), online booking, walk-ins, start code, no-show and strikes, breaks, leave, closed days, pause, the automatic end |
| `notifications` | Messages queued with the change they report, sent by a job with retries as Web Push (browser notifications), and as SMS for each message type whose template id is set; an in-app message list per app with unread counts |

## How file handling works

Photos and PDFs live in **Cloudflare R2**. **Neon Postgres** keeps one small row per file (`files` table).
The phone never gets the R2 keys: it receives short-lived signed links instead.

### Where things live

```mermaid
flowchart LR
    App["Shop app<br/>(phone)"] -- "POST /api/v1/files<br/>GET /api/v1/files/{id}/link" --> API["FileController"]
    API --> SVC["FileService<br/>type check · sha256 · duplicates"]
    SVC --> REPO["StoredFileRepository"]
    REPO --> DB[("Neon Postgres<br/>files table")]
    SVC --> STORE["FileStorage interface"]
    STORE --> R2IMPL["R2FileStorage<br/>(AWS S3 library)"]
    R2IMPL --> R2[("Cloudflare R2<br/>bucket apnidukan-files")]
    App -. "opens signed link<br/>(downloads directly)" .-> R2
```

### Upload: `POST /api/v1/files`

```mermaid
flowchart TD
    A["Phone sends photo/PDF<br/>+ purpose (SHOP_PHOTO, OWNER_PHOTO, STAFF_PHOTO, BILL, RECEIPT)"] --> B{"Empty or over 10 MB?"}
    B -- yes --> E1["400 EMPTY_FILE / 413 FILE_TOO_LARGE"]
    B -- no --> C["Pass 1: read the file once<br/>first 12 bytes + SHA-256 fingerprint"]
    C --> D{"Real type from bytes:<br/>JPEG, PNG, WEBP or PDF?"}
    D -- no --> E2["400 UNSUPPORTED_FILE_TYPE"]
    D -- yes --> F{"Same shop + purpose + SHA-256<br/>already in files table?"}
    F -- yes --> G["200, duplicate: true<br/>returns the earlier file, nothing stored"]
    F -- no --> H["Pass 2: stream bytes to R2<br/>key: shops/{shopId}/{folder}/{uuid}.{ext}"]
    H --> I["Insert row in files table"]
    I -- saved --> J["201 with fileId"]
    I -- "same file saved at the same moment<br/>(unique index)" --> K["Delete our copy from R2<br/>200, duplicate: true"]
    I -- "database error" --> L["Delete our copy from R2<br/>500 INTERNAL_ERROR"]
```

### View or download: `GET /api/v1/files/{id}/link`

```mermaid
sequenceDiagram
    participant P as Phone
    participant B as Backend
    participant D as Neon (files)
    participant R as Cloudflare R2
    P->>B: GET /files/{id}/link (shop from the sign-in token)
    B->>D: find file by id AND shop
    alt another shop's file or unknown id
        D-->>B: nothing
        B-->>P: 404 NOT_FOUND
    else this shop's file
        D-->>B: storage key
        Note over B: builds a signed link in memory<br/>(valid 5 minutes, nothing stored)
        B-->>P: { url, expiresAt }
        P->>R: GET url
        R-->>P: the photo, streamed directly
    end
```

### Delete: `DELETE /api/v1/files/{id}`

```mermaid
flowchart TD
    A["DELETE /files/{id}"] --> B{"File belongs to this shop?"}
    B -- no --> E1["404 NOT_FOUND"]
    B -- yes --> C{"Attached to a bill, payment<br/>or shop record?"}
    C -- yes --> E2["409 FILE_IN_USE<br/>nothing deleted"]
    C -- no --> D["In one transaction:<br/>delete the row from Neon"]
    D --> F["Delete the bytes from R2"]
    F -- ok --> G["Commit · 204 No Content"]
    F -- "R2 fails" --> H["Roll back: row stays<br/>500, try again later"]
```

## How sign-in works

No passwords and no Google sign-in. The phone number is the account; an email is optional and only used for recovery.
One-time codes live in **Redis Cloud** (never in Postgres); accounts and refresh tokens live in **Neon**.

### Sign in with phone OTP

```mermaid
sequenceDiagram
    participant P as Phone
    participant B as Backend
    participant R as Redis Cloud
    participant D as Neon
    P->>B: POST /auth/otp/send { phone }
    B->>R: count sends (max 3 per 10 min)
    B->>R: save keyed hash of a 6-digit code (5 min)
    B-->>P: 202 (code goes by SMS)
    P->>B: POST /auth/otp/verify { phone, code }
    B->>R: compare hash
    alt wrong code (1st-4th)
        B-->>P: 400 WRONG_CODE, attemptsLeft
    else 5th wrong code
        B->>R: delete the code
        B-->>P: 429 TOO_MANY_ATTEMPTS (ask for a new code)
    else correct
        B->>R: delete the code (works once)
        B->>D: find or create the user, store refresh token hash
        B-->>P: accessToken (15 min) + refreshToken (30 days) + newUser
    end
```

### Staying signed in

"Sign out everywhere" and account recovery also forget every browser registered for notifications, so a lost
phone stops getting booking messages (with their start codes) too. If Redis is down: new tokens are still issued
(signing in and refreshing keep working), and a sign-out everywhere still answers 204 (done in the database;
access tokens in use run out within 15 minutes).

Codes: when SMS is paused (daily cap), asking for a new code is refused before anything changes, so the code
already sent keeps working. Recovery checks both codes before using either up, so a typo in one keeps the other.

```mermaid
flowchart TD
    A["Every API call:<br/>Authorization: Bearer accessToken"] --> B{"Token valid?"}
    B -- "missing" --> E1["401 NOT_SIGNED_IN"]
    B -- "expired (after 15 min)" --> C["Phone calls POST /auth/refresh<br/>with its refreshToken"]
    B -- "yes" --> OK["Request runs as this user and shop"]
    C --> D{"Refresh token active?"}
    D -- yes --> F["Old one is used up,<br/>new pair returned"]
    D -- "already used before<br/>(probably copied)" --> G["Every session of this person ends<br/>401 REFRESH_TOKEN_REUSED"]
    D -- "unknown / expired / signed out" --> H["401 INVALID_REFRESH_TOKEN<br/>sign in again"]
```

`POST /auth/logout` ends one session. Admin pages (`/api/v1/admin/**`) need an admin account.

### Limits (against guessing and SMS abuse)

| What | Limit |
| --- | --- |
| Tries per code | 5 (the 5th wrong one destroys the code). Every try is counted before checking, so parallel guesses don't get extra tries |
| Codes per phone or email, per network | 3 per 10 minutes, 10 per day (20 per day from all networks together) |
| Wrong codes per phone or email, per network | 10 per day (50 from all networks together), then `429 LOCKED_FOR_TODAY` |
| Per network (IP; IPv6 counted per /64) | 10 code requests and 30 code checks per hour. The IP is the caller's real one, from the header set in `CLIENT_IP_HEADER` (see the checklist) |
| Robot check | Sign-in and recovery codes need a Cloudflare Turnstile token (`captchaToken`) once `TURNSTILE_SECRET_KEY` is set (400 `CAPTCHA_REQUIRED` / `CAPTCHA_FAILED`). If Cloudflare doesn't answer, the code is sent anyway; the limits still hold |
| Email codes per account | 10 per day |
| All SMS together | `SMS_DAILY_LIMIT` per day (default 500), then `503 SMS_PAUSED`. At 80% an error is logged, so an alert can fire before codes stop |
| JSON request body | 64 KB (only `POST /api/v1/files` may be larger, up to 10 MB) |

Limits count per phone **and** network, so a stranger guessing your number from their own network locks only
themselves. Every 429 carries a `Retry-After` header (seconds to wait).

"Signed out everywhere" (recovery, or a copied refresh token) takes effect at once: every access token carries a
session number, and older numbers are refused on their next call. A swapped refresh token coming back within
60 seconds counts as the app retrying (a reply lost on a weak network); later than that it counts as copied.
Only a refresh token that was already swapped
coming back counts as copied; an old device retrying a signed-out token just gets "sign in again".

### Endpoints (`/api/v1/auth`)

| Call | What it does |
| --- | --- |
| `POST /otp/send` `{phone, captchaToken}` | Sends a code. `+91` and a leading `0` are fine. 429 after 3 codes in 10 minutes |
| `POST /otp/verify` `{phone, code}` | Signs in (creates the account the first time) |
| `POST /refresh` `{refreshToken}` | New token pair |
| `POST /logout` `{refreshToken}` | Ends this session, 204 |
| `POST /logout-all` | Signs out every device at once, this one too (204) |
| `GET /me` · `PATCH /me {name}` | The signed-in person · set your name (salons see it on bookings) |
| `POST /me/delete-code` · `POST /me/delete {code}` | Delete your account: a code to your own phone, then 204. 409 `SHOP_OWNER` for a shop owner (close or hand over the shop first). See below |
| `POST /email/send-code` `{email, phoneCode?}` | Sends a 6-digit code to the email (10 min). Replacing an already verified email also needs `phoneCode` |
| `POST /email/change-phone-code` | Sends a code to your own phone, needed to replace a verified email. The old email is told after the change |
| `POST /email/verify` `{email, code}` | Marks the email verified |
| `POST /recover/start` `{email, newPhone, captchaToken}` | Lost phone: a code to the verified email and one to the new phone |
| `POST /recover/complete` | Both codes right: moves the account to the new phone and ends every old session |

### Finishing sign-up

The app asks, in this order: phone → OTP (`otp/verify`, `newUser: true` the first time) → email → its code
(`/email/send-code`, `/email/verify`; the email is how a lost phone number is recovered) → "What should we call you?"
(`PATCH /me {name}`). `GET /me` shows `emailVerified` and `name`, so the app can resume where the person stopped.
Until the email is verified and the name set, booking a salon and registering a shop answer
**403 `PROFILE_INCOMPLETE`** with `details.missing` (`["email", "name"]`, `["name"]`, ...); browsing works without
them. A barber linked by the owner needs nothing: his account is the owner's doing, and he signs in with the phone.

### Deleting an account

Refused with 409 while online booking is blocked (`BOOKING_BLOCKED`, `details.until`) or when a booking starts
within the hour or is in progress (`BOOKING_SOON`): deleting and signing up again with the same phone must not be a
way round a block or a late-cancel strike.

One account works across every part of the app (kirana, salon, ...), so deleting it never deletes rows other
records point at. Instead, in one transaction: each module closes what is still open for the person
(`AccountDeletionListener`: upcoming salon bookings are cancelled with no strike and the salon is told "a customer
deleted their account", a barber's sign-in is unlinked, walk-in access goes,
waiting messages are cancelled and the text of all their messages emptied, devices forgotten), a helper or
barber leaves their shop, every device is signed out and its device label cleared, and the `users` row loses its
phone, name and email (`deleted_at` is set). Past bookings, strikes and shop records stay, showing no name. A
booking or move waiting behind the deletion, or a token from before it, is refused (401). Messages to a salon about earlier
bookings (sent, or still waiting to go out) keep the name the salon saw then. The same phone can later sign up as a new
account. A module added later (kirana orders) plugs in its own `AccountDeletionListener` to cancel its open items.

## How shops work

A person registers one shop (for now) and becomes its **OWNER**; the owner adds helpers by phone.
Every shop gets a plain number as its code (1001, 1002, ...), given out by the database.

```mermaid
sequenceDiagram
    participant P as Phone
    participant B as Backend
    participant D as Neon
    P->>B: POST /shops {name, owner, address, pincode, lat/lng, GSTIN?, Udyam?}
    B->>D: shop (DRAFT, next code) + membership (OWNER)
    B-->>P: 201 shop
    P->>B: POST /auth/refresh
    B-->>P: new access token carrying shop + role
    P->>B: PATCH /shops/me {version, changed fields}
    alt version matches
        B->>D: save; Envers adds this new version to shops_aud, with who changed it
        B-->>P: 200 shop (version + 1)
    else someone saved in between
        B-->>P: 409 SHOP_CHANGED (reload and retry)
    end
```

| Call | What it does |
| --- | --- |
| `POST /api/v1/shops` | Registers your shop as `DRAFT`, you as owner. 409 `ALREADY_HAS_SHOP` if you have one. Then refresh your token |
| `GET /api/v1/shops/me` | Your shop, its status and your role (`myRole`). 404 `NO_SHOP` if none |
| `PATCH /api/v1/shops/me` | Owner only (403 `OWNER_ONLY`). Send `version` and only the fields to change; `""` removes GSTIN/Udyam |
| `PUT /api/v1/shops/me/photos` | Owner. `{version, ownerPhotoFileId?, shopPhotoFileId?}`: ids of photos uploaded to `POST /files` with purpose `OWNER_PHOTO` / `SHOP_PHOTO` |
| `GET /api/v1/shops/me/members` | Owner. Everyone in the shop: name, phone, role (OWNER, HELPER, BARBER), joined |
| `POST /api/v1/shops/me/helpers` · `DELETE /api/v1/shops/me/helpers/{userId}` | Owner. `{phone}`: adds a helper (an account is made if that phone has none; they sign in with OTP and find the shop). 409 `ALREADY_IN_A_SHOP`. Remove: membership and walk-in access go, their sessions end at once. A salon's helper sees every barber, starts (also without code), cancels, reassigns and adds breaks; in a kirana the helper's billing and stock rights come with the kirana work |
| `POST /api/v1/shops/me/leave` | A helper or barber leaves the shop (204), e.g. a phone added without asking. A barber's profile, hours and bookings stay with the salon; only his sign-in goes. 409 `OWNER_CANT_LEAVE` |
| `POST /api/v1/shops/me/submit` | Owner. `{version}`: DRAFT → PENDING_VISIT. 409 `PHOTOS_REQUIRED` until both photos are set |
| `GET /api/v1/admin/shops?status=` | Field team (admins) only, else 403. Shops in a status (default `PENDING_VISIT`) with links to both photos. The admin website uses `?type=&group=` instead (see "The admin website") |
| `POST /api/v1/admin/shops/{id}/approve` | Field team. `{version, note?}` from the page they checked: PENDING_VISIT → APPROVED after the visit. 409 `SHOP_CHANGED` if the owner changed something since (reload, check, approve), 409 `WRONG_STATUS` from another status. Customers can find it from now on |
| `POST /api/v1/admin/shops/{id}/reject` | Field team. `{version, reason, note?, checklist?}`: the visit didn't pass but can be fixed, PENDING_VISIT → DRAFT. The owner sees `statusReason`, fixes it and submits again. Bookings already made stay (suspend to cancel them) |
| `POST /api/v1/admin/shops/{id}/suspend` | Field team. `{version, reason, note?, checklist?}`: APPROVED or PENDING_VISIT (e.g. found shut at its visit) → SUSPENDED, off the app. A salon's upcoming online bookings are cancelled and each customer is told ("isn't taking online bookings for now"; the field team's reason goes only to the owner). The salon can still run its own day (walk-ins) |
| `POST /api/v1/admin/shops/{id}/reinstate` | Field team. `{version, note?}`: SUSPENDED → PENDING_VISIT, back in the visit list; live again only once approved after a new check (so nothing changed while suspended goes live unchecked). Cancelled bookings stay cancelled |

The owner gets a message for each field-team decision (approved, sent back, suspended, reinstated).

Rules: the GPS pin is required and must be inside India; pincode is 6 digits; GSTIN and Udyam are optional, checked,
and stored in capitals; text is trimmed. Unknown fields are refused with 400 `UNKNOWN_FIELD`, so nobody can send
`"status": "APPROVED"` for their own shop. Status moves DRAFT → (both photos, Submit) → PENDING_VISIT → (field team
visits and approves) → APPROVED. Changing the photos or the address (address line, area, city, pincode, map pin)
of an APPROVED shop sends it back to PENDING_VISIT for another check: customers can't find it until the field team
approves it again. Bookings already made stay and can be cancelled; moving one to a new time answers 409
`SALON_NOT_BOOKABLE` until the approval (`canMove` is false meanwhile). A new address rewrites the pending booking
reminders, so customers are sent to the new place. A photo that a shop or barber uses can't be deleted
(409 `FILE_IN_USE`).

A shop is a **kirana** or a **salon**: send `"type": "SALON"` when registering (left out = `KIRANA`). The type can't
be changed later; `GET /shops/me` returns it.

## The admin website (`/api/v1/admin`)

For Apni Dukan's own team. Everything here needs an admin's sign-in (the normal phone OTP); anyone else gets 403.
Who is an admin is read from the database on every call, so someone removed is refused at once even with a token
issued before.

**Roles** (`users.admin_role`): `ADMIN` (field team: visits and support) and `SUPER_ADMIN` (also the team and OTP
test mode). There is at most one super admin (the database refuses a second). It is set by hand, once, in Neon:

```sql
UPDATE users SET admin_role = 'SUPER_ADMIN', admin = true WHERE phone = '9xxxxxxxxx';
```

(Sign in once first, so the account exists. `admin` is the old column, kept in step for a version still running
during a deploy.) The super admin is never changed from the website and can't delete their account (409
`SUPER_ADMIN`): without them nobody could manage admins or switch test mode off.

| Endpoint | Notes |
| --- | --- |
| `GET /admin/me` | Who is signed in: `{userId, name, phone, adminRole, testMode: {on, until}}` (the website shows a red banner while test mode is on) |
| `GET /admin/dashboard` | For `KIRANA` and `SALON`, how many shops are in each group: `FIRST_VISIT` (waiting, never approved), `RECHECK` (waiting, approved before: the owner moved the pin or changed a photo, or it was reinstated), `SENT_BACK` (a draft sent back with a reason), `LIVE`, `SUSPENDED`. A draft never submitted is in no group |
| `GET /admin/shops?type=&group=&q=` | A group's shops, longest in it first (`statusSince`: its latest timeline entry other than a note, so an owner fixing a typo doesn't move it), at most 100, each with `group` and `ownerPhone`. `q`: the shop number exactly (`1042` or `#1042`), the owner's phone in part (spaces, `+91` and dashes ignored), or the name or owner's name in part |
| `GET /admin/shops/{id}` | The shop; a salon's barbers and services; `changes` since the moment it was last approved (name, owner, address, area, city, pincode, pin with `pinMovedMetres`, GSTIN, Udyam, photos with the old photo links), so edits a live shop made without needing a visit show up too; the `timeline`, newest first |
| `POST /admin/shops/{id}/notes {note}` | A note only admins see (2–500 characters), 201 |
| decisions | approve, reject (send back), suspend, reinstate as in "How shops work". The status is checked before the version: when another admin decided first, the answer is 409 `WRONG_STATUS` with `details.status`, not `SHOP_CHANGED`. Now with an optional `note` (admins only, up to 500 characters) and, for send back and suspend, an optional `checklist`: `NAME_BOARD_NOT_VISIBLE`, `OWNER_PHOTO_UNCLEAR`, `PIN_WRONG`, `ADDRESS_MISMATCH`, `GST_UDYAM_MISMATCH`, `SHOP_SHUT`, `OTHER`. The owner sees only the `reason` |
| `GET /admin/team` · `POST /admin/team {phone}` · `DELETE /admin/team/{userId}` | Super admin only (403 `SUPER_ADMIN_ONLY` for an admin). `since` is when they became an admin. Add makes that phone an admin (the account is made if it has none) and ends any session already open on it; 409 `ALREADY_ADMIN`, 409 `TEST_PHONE` (a test number, or an account made in test mode). Remove: their sessions end at once; 409 `CANT_REMOVE_SUPER_ADMIN` |
| `GET` · `PUT {hours}` · `DELETE /admin/test-mode` | Super admin only. OTP test mode on for 1–8 hours (it switches itself off), or off now |
| `POST /admin/test-mode/phones {phone, label}` · `DELETE /admin/test-mode/phones/{phone}` | Super admin only. The test numbers. 409 `ADMIN_PHONE` (an admin's number), 409 `REAL_ACCOUNT` (a number that already has a real account), 409 `ALREADY_TEST_PHONE`. Removing one signs its test account out |

**OTP test mode.** While it is on, the test numbers sign in with any 6 digits and no SMS is sent to them. Only a
number nobody has signed in with can be added; the account its first test sign-in makes is a test account
(`users.test_account`), and only such an account ever takes any code, never a real customer's, a shop's or an
admin's. A test account stays signed in only while test mode is on for its number: switching it off, the time
running out, or removing the number ends its sessions (refresh answers 401 `TEST_MODE_OFF`). If the number's real holder
later signs in with the SMS code (or the account is recovered onto a real phone), it becomes a normal account for
good and every tester still signed in to it is signed out. Email codes, recovery
and account deletion always need the real code (otherwise a test account could claim someone's email). For testing
on the real deployment without SMS credit; like the OMR project's per-student OTP bypass, but switched on for a few
hours at a time. Every change (admins, test numbers, test mode on and off) is logged in `admin_events`.

**Timeline** (`shop_events`): sent for a visit (owner), changed after approval (owner), approved, sent back,
suspended, reinstated, note. Shops registered before it existed have an empty timeline and count their waiting time
from registration. The photos a shop had when last approved can't be deleted while it waits for its re-check (409
`FILE_IN_USE`): the field team compares them with the new ones.

## How salon setup works

A salon lists its services, its barbers, which services each barber does and when each one works. Online bookings
are worked out from exactly these. Anyone in the salon can read the setup; only the owner can change it.

| Call | What it does |
| --- | --- |
| `GET` / `POST /api/v1/salon/services` | List (by `sortOrder`, then name) / add: `name`, `durationMinutes` (5–480, steps of 5), `pricePaise`, `priceIsFrom`, `sortOrder` |
| `PATCH /api/v1/salon/services/{id}` | Edit, or `"active": false` to switch off (kept for old bookings). Send `version`; 409 `SERVICE_CHANGED` if stale |
| `GET` / `POST /api/v1/salon/staff` | Barbers with their `serviceIds`, `hours`, `loginPhone`, `photoFileId` / add one: `name`, and optionally `phone` to give him his own sign-in |
| `PATCH /api/v1/salon/staff/{id}` | Rename, or `"active": false` when a barber leaves (409 `STAFF_HAS_BOOKINGS` while he has upcoming ones; his sign-in is removed). Switching back on: 409
`BLOCK_CONFLICTS` if a leave of his runs into a closed day (remove it, switch him on, add it again around that day). Send `version`; 409 `STAFF_CHANGED` if stale |
| `PUT` / `DELETE /api/v1/salon/staff/{id}/login` | `{phone}`: his sign-in becomes a BARBER member of the salon (409 `ALREADY_IN_A_SHOP` if that phone works in a shop). Delete: membership and walk-in access go, his sessions end at once |
| `PUT /api/v1/salon/staff/{id}/photo` | Optional photo: `{fileId}` of a `STAFF_PHOTO` upload, or `{"fileId": null}` to remove |
| `PUT /api/v1/salon/staff/{id}/services` | `{"version": 3, "serviceIds": [...]}` replaces what he does; newly added ones must be active services of this salon. 409 `BOOKINGS_IN_THE_WAY` (listing them) if an upcoming booking of his uses a service he'd drop |
| `PUT /api/v1/salon/staff/{id}/hours` | `{"version": 3, "hours": [{"weekday": 1, "start": "10:00", "end": "14:00"}, ...]}` replaces his week. 409 `BOOKINGS_IN_THE_WAY` (listing them) if an upcoming booking of his would fall outside the new hours: reassign or cancel it first |
| `GET` / `PATCH /api/v1/salon/settings` | Earliest booking (`minLeadMinutes`, default 30) and latest (`maxDaysAhead`, default 7). `version` is null until the first save |

**Database:** V4 runs `CREATE EXTENSION IF NOT EXISTS btree_gist` (needed by the no-overlap rule). Neon and the
Docker image used by the tests have it; on your own Postgres, the role running Flyway must be allowed to create it.

**Time:** every salon is in India (`Asia/Kolkata`, no daylight saving). Hours are `"HH:mm"` in salon time on
15-minute steps, `weekday` 1 = Monday ... 7 = Sunday, up to `"24:00"`; two blocks on one day make a split shift. They
are stored as minutes after midnight (10:00 = 600), so the database itself refuses overlapping blocks
(`ex_staff_hours_no_overlap`). Exact moments are stored in UTC and returned with their offset.

**Safety:** every call checks in the database that you belong to a salon (404 `NO_SHOP`, 409 `NOT_A_SALON`) and,
for changes, that you are its owner (403 `OWNER_ONLY`). Services and barbers are always looked up together with your
salon, so another salon's ids give 404; the database's keys also refuse linking a barber to another salon's service.
Changing a barber's services or hours raises his `version`, and both calls need the version you last read: two edits
from the same old screen run one after the other (row lock) and the second gets 409 `STAFF_CHANGED` instead of
silently replacing the first.

**Limits and errors:** up to 100 active services and 50 active barbers per salon (switched-off ones don't count):
409 `TOO_MANY_SERVICES` / `TOO_MANY_STAFF`, also when switching one back on. Two active services can't share a name,
capitals ignored: 409 `SERVICE_NAME_TAKEN`. Another salon's ids: 404 `SERVICE_NOT_FOUND` / `STAFF_NOT_FOUND`; in
`serviceIds` of `PUT .../services`, another salon's or a switched-off service is 400 `VALIDATION_FAILED` naming
`serviceIds`. A stale settings save is 409 `SETTINGS_CHANGED`. The limits are a guard against runaway lists, not
exact: two adds at the very same moment could both pass at 99.
Separately, one request carries at most 200 `serviceIds` and 50 blocks of `hours` (more is 400 `VALIDATION_FAILED`).

## Run it on your laptop

1. **Install:** JDK 21 (IntelliJ can download it). IntelliJ's bundled Maven is enough.
2. **Settings:** every `${...}` name in `src/main/resources/application.properties` needs a value.
   Put them in IntelliJ: Run → Edit Configurations → `ApniDukanApplication` → Environment variables
   (or in a `.env` file in this folder; it's ignored by Git).
   - `DB_URL`, `DB_URL_DIRECT`, `DB_USER`, `DB_PASSWORD` from Neon (use the `dev` branch).
     The pooled URL has `-pooler` in the host; the direct one doesn't.
   - `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY`, `R2_SECRET_KEY` from Cloudflare R2.
   - `REDIS_URL` from Redis Cloud, like `rediss://default:<password>@<host>:<port>`
     (`rediss` with two s = TLS; use `redis://` only if your database has TLS off).
   - `JWT_SECRET`: a long random value, at least 32 characters
     (for example the output of `openssl rand -base64 48`). Changing it signs everyone out.
   - `MSG91_AUTH_KEY` and `MSG91_OTP_TEMPLATE_ID`: the same values as in the OMR project. OTPs are sent as
     real SMS through MSG91 with that template until Apni Dukan has its own DLT registration.
     Later: `SMS_ACCOUNT_MOVED_ENABLED=true` with `MSG91_ACCOUNT_MOVED_TEMPLATE_ID`, the template for
     "your account was moved to a new number ending ##ENDING##" (sent to the old phone after account recovery).
     Off by default until that DLT template exists; required on the real server.
     `SMS_PROVIDER=log` prints SMS in the console instead (only if you choose it, e.g. out of MSG91 credit).
   - `MAIL_USERNAME` (your Gmail address) and `MAIL_PASSWORD` (its 16-letter app password), the same as in
     Vidyavriti. Email codes are sent through Gmail SMTP. `MAIL_ENABLED=false` prints them in the console instead.
   - Optional: `CORS_ALLOWED_ORIGINS`
     (default `http://localhost:5173`, the shop app's dev server; separate several with commas).
   - On the real server only: `APP_PRODUCTION=true`. The app then refuses to start if codes would be printed in
     the log or CORS allows localhost. Optional `SMS_DAILY_LIMIT` (default 500).
   Never commit real values.
3. **Run:** open this folder in IntelliJ, wait for Maven to download, then press ▶ next to `main` in
   `ApniDukanApplication.java`. On first start, Flyway creates the tables (`V1__create_files.sql`, `V2__create_users_and_refresh_tokens.sql`).
4. **Tests:** `mvn test` (or right-click `src/test/java` → Run tests). They start their own Postgres, Redis and S3
   stand-in in Docker (Testcontainers), so Docker must be running; they never touch Neon, Redis Cloud or R2.
   Without Docker the integration tests are skipped on your laptop (on CI they always run).
   `mvn verify` also runs the code checks (coverage gate, SpotBugs). It needs Docker, because without the integration
   tests the coverage gate fails; use `mvn verify -Djacoco.skip=true` if Docker isn't running.

## Try it

**Sign in** (the code arrives by SMS through MSG91)

```bash
curl -X POST http://localhost:8080/api/v1/auth/otp/send \
  -H "Content-Type: application/json" -d '{"phone":"9876543210"}'

curl -X POST http://localhost:8080/api/v1/auth/otp/verify \
  -H "Content-Type: application/json" -d '{"phone":"9876543210","code":"<code from SMS>"}'
```

Copy `accessToken` from the reply and send it on every other call:

```bash
curl http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer <accessToken>"
```

**Files** need a shop: register one (`POST /api/v1/shops`, see "How shops work"), then refresh your token.
Without a shop, file calls answer `403 SHOP_REQUIRED`.

```bash
# Upload a bill photo: 201 with the file id; the same file again returns 200 and "duplicate": true
curl -X POST http://localhost:8080/api/v1/files -H "Authorization: Bearer <accessToken>" \
  -F "purpose=BILL" -F "file=@/path/to/bill.jpg"

# Link to view it for 5 minutes (add ?download=true to save instead)
curl "http://localhost:8080/api/v1/files/<id>/link" -H "Authorization: Bearer <accessToken>"

# Delete it from Neon and R2: 204, or 409 FILE_IN_USE if attached to a bill, payment or shop record
curl -X DELETE "http://localhost:8080/api/v1/files/<id>" -H "Authorization: Bearer <accessToken>"
```

`purpose` is one of `SHOP_PHOTO`, `OWNER_PHOTO`, `STAFF_PHOTO` (a salon barber), `BILL`, `RECEIPT`. A barber's
sign-in can't use files at all (403). Accepted types: JPEG, PNG, WEBP, PDF (checked from the
file's bytes, not its name), up to 10 MB. Another shop's file returns `404`.

## How salon bookings work

No money moves: customers book a time and pay the salon. One table, `appointments`, holds bookings, walk-ins and
blocks (break, leave, closed day). While a row holds a barber's time (CONFIRMED, IN_SERVICE or BLOCKED), the
database's rule `ex_appointments_no_overlap` stops anything else of that barber overlapping it. That rule, not Java,
decides every race.

```mermaid
sequenceDiagram
    participant C as Customer app
    participant B as Backend
    participant D as Neon
    C->>B: GET /salons?lat&lng, /salons/{id}/services, /salons/{id}/availability?services&date
    B-->>C: free start times (quarter hours)
    C->>B: POST /bookings {salonId, serviceIds, startAt} + Idempotency-Key
    B->>D: lock this customer; same key? strikes? 2 active? window?
    loop free barbers, least booked that day first
        B->>D: INSERT ... ON CONFLICT DO NOTHING
    end
    B->>D: services copied, event BOOKED, messages CONFIRMED (+ REMINDER at start - 60 min)
    B-->>C: 201 booking with start code (or 409 SLOT_TAKEN + 3 nearest times)
```

**Customer (any signed-in person):** `GET /salons?lat=&lng=` (approved, not paused, within 2 km, nearest first, 30
a page; the next page with the last salon's `distanceMetres` and `id` as `afterDistance` and `afterId`; see "Built for
scale"),
`GET /salons/{id}`, `/services`, `/availability?services=a,b&date=YYYY-MM-DD`, `POST /bookings` (header
`Idempotency-Key`: a new UUID per attempt, the same one on retry), `GET /me/bookings?when=upcoming|past`,
`GET /bookings/{id}`, `POST /bookings/{id}/cancel`, `POST /bookings/{id}/reschedule {startAt}` (not in the last hour:
409 `TOO_LATE_TO_MOVE`, so moving can't dodge a late-cancel strike; `canMove` says when), `PATCH /auth/me {name}`.
The customer picks services and a time; the backend picks the barber. Another person's booking is 404.

**Salon (`/api/v1/salon/...`):**

| Call | Who | What it does |
| --- | --- | --- |
| `GET /day?date=` | owner, helper, and anyone with walk-in access: all barbers; other barbers: their own | A column per barber with his hours and appointments (`notStarted` once its time passed), and `myStaffId` (the caller's own barber profile, null for owner and helpers). Never the start code |
| `GET /upcoming` | owner, helper, anyone with walk-in access: the salon; other barbers: their own | Online bookings still to come across the next 31 days, soonest first (up to 200), including ones running late |
| `GET /history?from=&to=` | owner, helper | India dates, at most 31 days: bookings and walk-ins latest first (up to 500), how many ended in each status, and what the completed ones were worth at list price |
| `GET /walk-ins/options?services=` · `POST /walk-ins` | owner, and people he gave walk-in access | Per barber: free now, or when he is next free today (exact minutes, for information). A walk-in always starts now with a barber who is free now (IN_SERVICE at once, no code: the customer is in the chair); there is no booking a walk-in for later. 409 `BARBER_BUSY` with his next free time |
| `GET /members` · `PUT /members/{userId}/walk-in-access {allowed}` | owner | Who may add walk-ins besides the owner (a helper or barber) |
| `POST /appointments/{id}/start` | owner, helper; barber: own | From 15 min before until 15 min after the start (then it is a no-show). A booking needs `{code}` (400 `WRONG_START_CODE` with `triesLeft`; 5 wrong → 429 `START_CODE_LOCKED`) or `{withoutCode: true}` (owner, helper). Started early, it holds the barber from now for its length; if someone (or a break) still holds his chair, 409 `BARBER_BUSY` (`reason: STILL_BUSY`). Started late, it runs its full length from now when he is free for that, else keeps its planned end |
| `POST /appointments/{id}/finish` | owner, helper; barber: own; and whoever added a walk-in that is still in the chair | Done before the planned end: IN_SERVICE → COMPLETED now, and the rest of the barber's time is free |
| `POST /appointments/{id}/no-show` | owner, helper; barber: own | From 15 min after the start (the late allowance). Frees the time; a booking gives the customer a strike. Rarely needed: the job below does it within a minute |
| `POST /appointments/{id}/undo-no-show` | owner, helper | Within 24 h of the start: the strike goes, the booking is closed as cancelled by the salon ("No-show undone"), and the customer is told it no longer counts. Later, only support can remove the strike |
| `POST /appointments/{id}/reassign {staffId}` · `/cancel {reason}` | owner, helper; and whoever added a walk-in that is still in the chair (while he still has walk-in access) | Another barber who does its services: a booking at the same time; a walk-in already in the chair (added to the wrong barber) continues with him from now for the rest of its time; both barbers and an online customer are told · salon cancel of a booking, a walk-in, or a walk-in in the chair within 15 minutes of it being put in a chair, which a move doesn't reset (later: 409 `IN_CHAIR_TOO_LONG`, finish it instead; the owner and that chair's barber are told when someone else cancels one). A booking's customer and barber are told; no strike |
| `GET /staff/{id}/blocks/conflicts` · `POST /staff/{id}/blocks` · `DELETE .../blocks/{blockId}` | break: owner, helper, the barber; leave: owner | 409 `BLOCK_CONFLICTS` listing what's in the way; nothing saved. A barber's own breaks: today or tomorrow, at most 4 hours a day in all (longer time off is leave); he removes only breaks he added (`addedByMe` on each appointment says which; on a walk-in it is set only while the viewer may add walk-ins, so it marks what he may fix) and only before they start (409 `WRONG_STATUS`, `reason: STARTED`) |
| `GET /closures` · `POST /closures {date}` · `DELETE /closures/{id}` | read: anyone in the salon; change: owner | A whole day closed for every barber, all or nothing; barbers added or switched on later get it too |
| `GET /pause` · `POST /pause {until?}` · `POST /resume` | read: anyone in the salon; change: owner, helper | Stop new online bookings (default: until midnight) |

On the salon's screens a booking shows the customer's name and, for the owner and helpers only (never barbers),
`customerPhone`, to call someone who is running late.

**Support (`/api/v1/admin/salon`, admins only):** `POST /strikes/lookup {phone}` lists a customer's strikes (latest
first, with salon and booking number) and `blockedUntil`; `POST /strikes/{appointmentId}/remove {reason}` takes
one back (204; the reason goes into the booking's history as `STRIKE_REMOVED`).

**Lifecycle:** CONFIRMED → IN_SERVICE (Start, with the customer's code) → COMPLETED by itself at its planned end (a
job every minute), or earlier with Finish. CONFIRMED →
CANCELLED_BY_CUSTOMER / CANCELLED_BY_SALON. A booking not started 15 minutes after its start becomes
NO_SHOW by itself (the same job; a customer being served has always been started with their code by then): an online
booking gives the customer a strike and a "missed booking" message. BLOCKED → REMOVED. Every change is a
conditional update ("only if still CONFIRMED") and is logged in `appointment_events` with who did it.

**Refusals say why:** 409 `WRONG_STATUS` carries `details.reason`: `TOO_EARLY` (with `startFrom` or `noShowFrom`),
`HELD_OVER` (with `heldUntil`), `TOO_LATE` (undo after 24 h), `CHANGED` (someone changed it first; with `status`),
`REASSIGNED` (a barber's appointment that was just given to another barber), `IN_CHAIR_TOO_LONG` (a walk-in cancelled
more than 15 minutes into its visit), `STARTED` (a barber removing his own break once it began).

**Rules:** 30 minutes' notice and up to 7 days ahead (the salon's settings); quarter hours only (400
`OUTSIDE_WINDOW`); at most 2 upcoming bookings per customer (409 `TOO_MANY_BOOKINGS`); a cancel less than 1 hour
before, or a no-show, is a strike; 3 strikes within 60 days block online booking until 30 days after the latest
(403 `BOOKING_BLOCKED`, walking in still works). At most 10 new bookings and moves per customer per 24 hours (429
`TOO_MANY_CHANGES`): each one messages the salon, so a book-and-cancel loop can't flood it or use up the SMS
budget. Only changes that happened count; a refused attempt (a taken time, a closed day) never uses one up. One
person can't hold two bookings at the same time (409 `OVERLAPS_YOUR_BOOKING`), booking or moving. The free times
for a move (`GET /salons/{id}/availability?...&moving={bookingId}`, the customer's own booking only, else 404)
count that booking's own time as free, so it can move by a few minutes. One person's bookings run one after the other (an advisory lock),
so tapping twice can't pass these limits.

**Messages:** written to `notification_jobs` in the same transaction as the change; a job every 30 s sends what is
due as Web Push to each of the person's devices (`POST /me/push-subscriptions`, the key from
`GET /push/public-key`), retrying after 1, 5 and 15 minutes. Confirmation now; reminder 60 minutes before (only for
bookings made more than 75 minutes ahead) with the address, map link, start code and "held until"; rescheduled;
cancelled. Moving or cancelling a booking cancels its pending messages. The salon is told too: when a customer
books, moves or cancels online (or deletes their account), the owner, the helpers (in the app only, never SMS) and the
barber it's with (if he has a sign-in) get a message; these are never cancelled by a later change, which sends its
own. When the owner or a helper gives a booking to another barber or cancels it, the barbers concerned are told (not
the person who did it). Someone who leaves a salon, is removed, or loses their barber sign-in loses its messages
(`SALON_*`) too: its customers' names aren't theirs to keep. The server only sends to the browsers' real
push services (Google, Mozilla, Apple, Microsoft), so a device address can't point it anywhere else.

**In-app message list:** every message is also kept for the app's own list (its PUSH row in `notification_jobs`,
`read_at` = opened), so people who blocked notifications or missed one can read it in the app. Kept 90 days (the
message cleanup). Each app sees only its own messages: `app=customer` (default) the `BOOKING_*` ones, `app=partner`
the `SALON_*` and `SHOP_*` ones. A message not due yet, or cancelled before it was due (a reminder for a booking
that was moved), isn't listed; one cancelled after it was due stays (it may already have been read). A reminder
whose text is rewritten (the salon moved) is unread again. Messages that name a customer to the salon carry
`about_user_id`, so deleting that customer's account blanks them too.

| Endpoint | Does |
| --- | --- |
| `GET /api/v1/me/notifications?app=&cursor=` | Newest first, 30 a page: `{items:[{id,type,title,body,url,sentAt,read}], unread, next}`; pass `next` as `cursor` for the next page (400 `BAD_CURSOR` if it's not one) |
| `GET /api/v1/me/notifications/unread-count?app=` | `{unread}`, for the bell |
| `POST /api/v1/me/notifications/read?app=` `{ids}` | Marks those read (only your own, at most 100); with no body or no ids, everything in that app's list (204) |

**SMS (optional, per message type, no code change):** create the template on DLT and in MSG91 with the variables
below, then set its id on the server (Railway) and restart. From then on every new message of that type also goes
out as one SMS to the customer's phone (its own row, retried on its own). Remove the setting again to switch that
type's SMS off. Each variable is at most 30 characters (DLT's limit). Spending guards: at most 10 booking SMS per
person per day and `SMS_BOOKING_DAILY_LIMIT` (default 300) in total per day, counted separately from sign-in codes;
over a limit the SMS is dropped (the Web Push still goes out) and logged (the total limit once a day). Both limits
run for 24 hours from the first SMS counted. They guard spending, they aren't exact counts: in rare cases (MSG91
accepts but its answer times out, or Redis fails right after a send) one SMS can go out twice.

| Message | Setting | MSG91 variables |
| --- | --- | --- |
| Booking confirmed | `SMS_TEMPLATE_BOOKING_CONFIRMED` | `##SALON##`, `##WHEN##` (Tue 10 Nov, 6:30 PM), `##BOOKING##` |
| Reminder, 60 min before | `SMS_TEMPLATE_BOOKING_REMINDER` | `##SALON##`, `##TIME##` (6:30 PM), `##AREA##`, `##CODE##`, `##UNTIL##` (6:45 PM) |
| Rescheduled | `SMS_TEMPLATE_BOOKING_RESCHEDULED` | `##SALON##`, `##WHEN##`, `##BOOKING##` |
| Missed (became a no-show) | `SMS_TEMPLATE_BOOKING_NO_SHOW` | `##SALON##`, `##WHEN##` |
| Cancelled by the customer | `SMS_TEMPLATE_BOOKING_CANCELLED` | `##SALON##`, `##WHEN##` |
| Cancelled by the salon | `SMS_TEMPLATE_BOOKING_CANCELLED_BY_SALON` | `##SALON##`, `##WHEN##`, `##REASON##` |
| To the salon: new booking | `SMS_TEMPLATE_SALON_NEW_BOOKING` | `##CUSTOMER##`, `##WHEN##`, `##BARBER##`, `##BOOKING##` |
| To the salon: moved | `SMS_TEMPLATE_SALON_BOOKING_MOVED` | `##CUSTOMER##`, `##WHEN##` (the new time), `##BARBER##`, `##BOOKING##` |
| To the salon: cancelled | `SMS_TEMPLATE_SALON_BOOKING_CANCELLED` | `##CUSTOMER##`, `##WHEN##`, `##BARBER##`, `##BOOKING##` |

In the app only (no SMS): `BOOKING_BARBER_CHANGED`, `BOOKING_NO_SHOW_UNDONE`, `SALON_BOOKING_REASSIGNED`,
`SALON_CANCELLED_BY_SALON`, `SALON_WALK_IN_CANCELLED` (to the owner).

Example DLT text for the reminder: "Reminder: {#var#} at {#var#} today, {#var#}. Show code {#var#} at the counter.
Held till {#var#}. - Apni Dukan". The SMS uses the same MSG91 account as the sign-in OTP.

## Before going live (checklist)

- **Real caller IPs:** set `CLIENT_IP_HEADER` on Railway: `X-Forwarded-For` (the backend takes its last entry,
  the one Railway adds), or `CF-Connecting-IP` if Cloudflare's proxy (orange cloud) sits in front; then also make
  the Railway address reachable only through Cloudflare, or anyone calling it directly can send a made-up
  `CF-Connecting-IP`. Then check one request's IP against your phone's. The real server refuses to start without it.
- **Robot check:** create a Cloudflare Turnstile widget for the app's domain; the app shows it on the sign-in and
  recovery screens and sends its token as `captchaToken`; set `TURNSTILE_SECRET_KEY` on Railway (required by the
  real server). Point an alert at the log line "80% of the daily SMS limit used".
- **Redis Cloud:** set the eviction policy to `noeviction`, so limits and session numbers are never dropped
  under memory pressure. If Redis stops answering, signed-in calls keep working; sign-in codes and start codes
  answer 503 `SERVICE_BUSY` until it is back (`Retry-After: 5`).
- `APP_PRODUCTION=true`, own DLT templates, `SMS_ACCOUNT_MOVED_ENABLED=true`, a mail provider with `MAIL_FROM`.
- **Web Push keys:** run `npx web-push generate-vapid-keys` once and set `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` (and
  `VAPID_SUBJECT`, e.g. `mailto:` your support address) on Railway. The real server refuses to start without them.
  Keep the pair: a new one silences every saved device until it subscribes again.

## Uploads: limits and photos

- At most **100 uploads per shop per day** (India day), then 429 `TOO_MANY_UPLOADS`. A duplicate of an earlier upload
  doesn't count.
- **Shop, owner and barber photos** must be JPEG or PNG. They are drawn again as a JPEG of at most 1280 px on the
  longest side: the phone's hidden details (EXIF: GPS position, phone model) are gone and the file is small. A file
  that only looks like a photo is 400 `UNREADABLE_PHOTO`. Bills and receipts are stored exactly as uploaded.

## Housekeeping

- **Health check:** `GET /actuator/health` answers `{"status":"UP"}` (no details; nothing else of the actuator is
  open). Set it as Railway's Healthcheck Path. It checks the database only: the app keeps serving while Redis is down.
- **Cleanup, every night (India time):** at 03:30 sign-ins that ended more than 30 days ago are deleted; at 03:40
  messages that finished more than 90 days ago. Bookings, strikes, events and the `*_aud` history stay.

## Errors

Every error looks like `{"code": "UNSUPPORTED_FILE_TYPE", "message": "...", "details": {}}`.
Sign-in errors use the same shape: `401 NOT_SIGNED_IN`, `401 TOKEN_EXPIRED`, `401 INVALID_TOKEN`, `403 FORBIDDEN`.
`409 TRY_AGAIN` means two requests changed the same thing at the same moment and the database undid this one:
nothing was saved, and sending it again works. `503 SERVICE_BUSY` (with `Retry-After`) means Redis or the database
didn't answer in time; nothing was done, try again in a few seconds.

## Built for scale

Written for tens of thousands of shops, lakhs of customers and several copies of the backend at once.

- **Nearby salons (PostGIS, V12).** Each shop's pin is also a PostGIS `geography` point (`shops.location`, computed
  by the database), with a map index on approved shops. One query finds the salons within 2 km
  (`ST_DWithin`), measures each in metres (`ST_Distance`, on a sphere: within 0.3%), leaves out paused ones, sorts
  nearest first and returns one page of 30. The next page starts after the last one's (distance, id) (keyset
  paging), so a dense city with hundreds of salons costs the same as a quiet one. Photos' links come from one read
  for the whole page.
- **Indexes (V13)** for every query that runs all the time: bookings waiting to start (the no-show job), a salon's
  day (overlap on `time_range`, so it reads that day only), a salon's upcoming bookings, finished messages and
  refresh tokens (cleanup), a person's messages (account deletion), shop name search (trigram), and foreign-key
  lookups.
- **Busy time** of barbers is read barber by barber with an overlap test, so the no-overlap index answers it from
  the few rows around that time.
- **Background jobs in batches, one server at a time.** The automatic end and no-show round closes 500 rows per
  short transaction until none are left, skipping rows another server or request holds (`FOR UPDATE SKIP LOCKED`).
  Each round takes a lease in `job_leases` (V14), so with several servers only one does it; a server that dies
  frees it when the lease runs out.
- **Tests** run on the PostGIS image of Postgres 16 (`postgis/postgis:16-3.4-alpine`), the same extensions as Neon.

## Database changes

Only through new Flyway files in `src/main/resources/db/migration` (next: `V15__...`, or later if the scale PR's
message changes took V15). `users.admin` (replaced
by `admin_role` in V9) is no longer read and can be dropped in a later migration.
A table whose entity has `@Audited` also needs its `*_aud` history table in the same migration (Envers doesn't
create tables, because Hibernate only validates).
Never edit a migration that has already run. Hibernate is set to `validate`.
