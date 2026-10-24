# Apni Dukan – backend

All the backend functionality of Apni Dukan: Spring Boot 4.1 · Java 21 · Neon Postgres · Flyway · Cloudflare R2

Design: see the "Apni Dukan – Low-Level Design (Shop App MVP)" doc.

## What's built so far

| Module | Status |
| --- | --- |
| `common` | Error format, current user/shop (development headers for now), clock |
| `files` | Upload photos and PDFs to R2, duplicate detection, 5-minute signed links |

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
    A["Phone sends photo/PDF<br/>+ purpose (SHOP_PHOTO, BILL, RECEIPT)"] --> B{"Empty or over 10 MB?"}
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
    P->>B: GET /files/{id}/link (shop from headers/token)
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

## Run it on your laptop

1. **Install:** JDK 21 (IntelliJ can download it). IntelliJ's bundled Maven is enough.
2. **Settings:** every `${...}` name in `src/main/resources/application.properties` needs a value.
   Put them in IntelliJ: Run → Edit Configurations → `ApniDukanApplication` → Environment variables
   (or in a `.env` file in this folder; it's ignored by Git).
   - `DB_URL`, `DB_URL_DIRECT`, `DB_USER`, `DB_PASSWORD` from Neon (use the `dev` branch).
     The pooled URL has `-pooler` in the host; the direct one doesn't.
   - `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY`, `R2_SECRET_KEY` from Cloudflare R2.
   - `DEV_AUTH_ENABLED=true`
   Never commit real values.
3. **Run:** open this folder in IntelliJ, wait for Maven to download, then press ▶ next to `main` in
   `ApniDukanApplication.java`. On first start, Flyway creates the `files` table (`V1__create_files.sql`).
4. **Tests:** `mvn test` (or right-click `src/test/java` → Run tests). They don't need the database or R2.

## Try the files API

Until sign-in exists (milestone 2), every call needs two made-up IDs as headers.
Use the same values every time so you see your own files:

```
X-Dev-Shop-Id: 11111111-1111-1111-1111-111111111111
X-Dev-User-Id: 22222222-2222-2222-2222-222222222222
```

**Upload a bill photo**

```bash
curl -X POST http://localhost:8080/api/v1/files \
  -H "X-Dev-Shop-Id: 11111111-1111-1111-1111-111111111111" \
  -H "X-Dev-User-Id: 22222222-2222-2222-2222-222222222222" \
  -F "purpose=BILL" \
  -F "file=@/path/to/bill.jpg"
```

Returns `201` with the file's `id`. Uploading the exact same file again returns `200` and `"duplicate": true`.
`purpose` is one of `SHOP_PHOTO`, `BILL`, `RECEIPT`. Accepted types: JPEG, PNG, WEBP, PDF (checked from the
file's bytes, not its name), up to 10 MB.

**Get a link to view it** (works for 5 minutes)

```bash
curl "http://localhost:8080/api/v1/files/<id>/link" \
  -H "X-Dev-Shop-Id: 11111111-1111-1111-1111-111111111111" \
  -H "X-Dev-User-Id: 22222222-2222-2222-2222-222222222222"
```

Open the returned `url` in a browser. Add `?download=true` to get a link that saves the file instead.
A different `X-Dev-Shop-Id` gets `404`: shops can't see each other's files.

## Errors

Every error looks like `{"code": "UNSUPPORTED_FILE_TYPE", "message": "...", "details": {}}`.

## Database changes

Only through new Flyway files in `src/main/resources/db/migration` (`V2__...`, `V3__...`).
Never edit a migration that has already run. Hibernate is set to `validate`.
