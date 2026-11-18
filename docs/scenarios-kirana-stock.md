# Kirana stock from photos and scans — scenario list (round 3, backend)

Everything a kirana owner or helper (and the Clozest team) can run into while getting stock in: distributors' bills
read by AI, scanning while shelving, unknown barcodes, corrections, the shared catalogue's trust, counter suggestions
and paper bills. "Screen" says what the partner app must show (the screens come with its kirana round).
Verdicts: **OK-tested**, **OK-untested**, **GAP**, **N/A-v1**, **INCONVENIENCE** (works, but annoying; with the fix).
Tests: `KST` = `KiranaStockIntegrationTest`, `NMT` = `NameMatcherTest`, `FIT` = `FilesIntegrationTest`.

Not in this round (needs the apps or a decision): scans sent while the bill is still being read (the app holds them
until the draft exists, then sends them with its `purchaseId`); paging and photos on the team's review list.

## 1. Photos read by AI

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1.1 | Helper photographs a distributor's bill (1–3 pages) | `POST /reads` 201 `READING`; read within ~15 s | "Reading the bill…" with the photo | KST aDistributorsBill… | OK-tested |
| 1.2 | The same photo sent twice (double tap, retry after a lost answer) | Same `clientRef`: the first upload, read once | Nothing new | (PhotoReads.queue) | OK-untested |
| 1.3 | A photo that isn't a bill (a selfie, a menu) | `FAILED`, "This doesn't look like a distributor's bill…" | The reason + "Take it again" | KST aPhotoThatIsNotABill… | OK-tested |
| 1.4 | Blurry or dark photo | `FAILED` with "Take a clear, straight photo … in good light" | Same | (ReadJob) | OK-untested |
| 1.5 | Another shop's file id sent | 400 naming `fileIds` | — (the app never does this) | KST aPhotoThatIsNotABill… | OK-tested |
| 1.6 | Another shop opens the read | 404 `READ_NOT_FOUND` | — | KST aPhotoThatIsNotABill… | OK-tested |
| 1.7 | AI or storage down, or anything unexpected | Retried after 1, 2, 4, 8, 16 min, then `FAILED` "send it again later or enter it by hand"; storage trouble uses no AI call | "Still reading — we'll keep trying" | — | OK-untested |
| 1.8 | AI key not set | `FAILED` at once, "isn't switched on yet. Enter it by hand." | Reason | — | OK-untested |
| 1.9 | A shop sends more than 30 photos a day | The rest wait until 00:05 tomorrow (`READING`), without losing a try or an AI call | "Waiting — read tomorrow morning" | KST aShopsDailyReadsWait… | OK-tested |
| 1.10 | 500 AI calls a day reached over all shops | Waits until tomorrow (the shop's 30 not used); logged | Same | — | OK-untested |
| 1.11 | Many servers run the job at once | Lease + `SKIP LOCKED` claim: each read once | — | — | OK-untested |
| 1.12 | 600+ bill/product photos a day | 429 `TOO_MANY_UPLOADS` (apart from the other 100) | "Too many photos today" | FIT aShopCanUploadAtMost100… | OK-tested |
| 1.13 | Throws a photo away | `POST /reads/{id}/discard`; a draft bill goes with it (what was shelved into stock); 409 `READ_USED` once used; 409 `READ_CHANGED` when its bill was made that very moment (try again) | "Removed" | KST aBillThrownAway… | OK-tested |
| 1.14 | A thrown-away or failed bill photo | Its file is deleted unless something else uses it | — | KST aBillThrownAway… | OK-tested |
| 1.15 | The bill came as Excel, CSV or a GST e-invoice JSON (WhatsApp, email) | Read in code: the draft with no AI call (free pieces added) | Same review screen | KST billsSentAsSpreadsheets… | OK-tested |
| 1.16 | A PDF from billing software (has text) | Sent to the AI as text, not pictures | — | KST aPdfBillWithText… | OK-tested |
| 1.17 | A Word file, an old .xls, a sheet whose columns aren't found, plain text | Sent to the AI as text | — | — | OK-untested |
| 1.18 | An iPhone HEIC photo; a file of another kind (zip, video) | 400 `UNSUPPORTED_FILE_TYPE` with what to send instead | The message | — | OK-untested |
| 1.19 | A PDF with a password, more than 20 pages, or text too long | `FAILED` with why | The reason | — | OK-untested |

## 2. Distributors' bills

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 2.1 | Lines clearly matching a product ("AMUL TAZA 500ML TM" → Amul Taaza Toned Milk 500 ml) | `pick: SURE`, product set | Ticked, can be changed | KST aDistributorsBill…, NMT | OK-tested |
| 2.2 | Another size on the bill than the shop's product | Never matched; `toCheck` | "Pick the product" with suggestions | KST, NMT aDifferentSize… | OK-tested |
| 2.3 | Another variant (Gold vs Taaza) | Never one tap | Suggestions | NMT aDifferentVariant… | OK-tested |
| 2.4 | Shop has no such product | Suggestions include catalogue items it doesn't have (`productId` null); `newProduct {itemId, pricePaise}` makes and picks it in one call | "Add as new product" on the suggestion | KST aBillLineForAProductTheShopLacks… | OK-tested |
| 2.5 | Adds while a line is still unpicked | 409 `LINES_TO_CHECK` naming lines | Scrolls to the first | KST | OK-tested |
| 2.6 | Two people edit the same bill | 409 `PURCHASE_CHANGED` | "Someone changed this bill — reloaded" | KST | OK-tested |
| 2.7 | Added once; tapped again | 409 `PURCHASE_DONE`; stock unchanged | "Already added" | KST | OK-tested |
| 2.8 | The same bill photographed again | 409 `BILL_ALREADY_ADDED` (with when); `addAgain` for another delivery | "Already added on … Add again?" | KST | OK-tested |
| 2.9 | The distributor's odd wording matched once | Remembered: next bill `REMEMBERED` | Ticked | KST | OK-tested |
| 2.10 | Distributor's name written differently ("M/S. Sharma Agencies") | Same key | — | KST | OK-tested |
| 2.11 | Cost price | Set from the rate; owner sees `costPaise`, helper null | Owner only | KST | OK-tested |
| 2.12 | A product removed meanwhile | 409 `PRODUCT_REMOVED` naming the lines | "Pick another" | — | OK-untested |
| 2.13 | Pieces vs kg: a fraction for a piece product | 400 naming `lines[n].quantity` | Inline | — | OK-untested |
| 2.14 | Skip a line (free sample, damaged) | `skip: true`; not added | Struck out | — | OK-untested |
| 2.15 | Two copies of one bill (owner and helper) added at the same moment | One adds; the other 409 `BILL_ALREADY_ADDED` (a lock on the bill's distributor and number); stock once | "Already added" | KST twoCopiesOfOneBill… | OK-tested |
| 2.16 | Accept all suggestions down a 30-line bill | `PATCH /purchases/{id}/lines` with many lines and one version | "Accept all" | (setLines) | OK-untested |
| 2.17 | A rate misread by the AI | `ratePaise` on the line (owner; helper 403 `OWNER_ONLY`) | Owner edits the rate | — | OK-untested |
| 2.18 | A line the AI missed; the bill number or date read wrong | `POST /purchases/{id}/lines`; `PATCH /purchases/{id}` | "Add a line", edit the top | — | OK-untested |
| 2.19 | A wrong product picked, noticed after adding (helper 403) | `POST …/lines/{n}/correct` (owner): stock moved (less what of the right product was scanned with the bill and added already), cost set, wording remembered | "Wrong product? Fix it" | KST aWrongPickCorrectedAfterAdding… | OK-tested |
| 2.20 | A helper looks at a bill | `ratePaise`, `amountPaise`, `totalPaise` null (what was paid is the owner's) | Quantities only | — | OK-untested |

## 3. Scanning while shelving

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 3.1 | Scans a pack | `ADDED`, stock after | Beep + "+1 Amul Taaza (28)" | KST scansAddPacks… | OK-tested |
| 3.2 | Scans a carton barcode (pack 12) | +12 | "+12" | KST | OK-tested |
| 3.3 | Misread (wrong check digit) | `REJECTED` "didn't scan right" | Buzz, "Scan again" | KST | OK-tested |
| 3.4 | Shop's loose-item label (weight or price) | `ADDED` kg | "+1.25 kg rice" | KST | OK-tested |
| 3.5 | Loose label for a piece product | `REJECTED` with why | Reason | — | OK-untested |
| 3.6 | Network drops; the app sends the batch again | `repeated: true`, nothing twice | — | KST | OK-tested |
| 3.7 | Many batches at once (two phones shelving) | Every piece once; one unknown entry per barcode | — | KST manyScanBatchesAtOnce… | OK-tested |
| 3.8 | Shelving with the bill open (draft), a product on one of its lines | `TICKED` with `scannedTotal` and `lineQuantity`; held with the bill (no stock yet); the bill adds its quantity once | "12 of 24" | KST scansWhileShelving… | OK-tested |
| 3.9 | Bill already added, scanning against it | 409 `PURCHASE_DONE` | "Scan without the bill" | KST | OK-tested |
| 3.10 | With the bill open, a product on no line yet (unmatched line, or not on the bill) | `HELD`: no stock; adding the bill adds the line's quantity once (picked) or the held quantity (on no line) | "Held with the bill" | KST scansWithABillAreNeverCountedTwice… | OK-tested |
| 3.11 | A product removed: before the scan, or in the moment between lookup and lock | Before: `UNKNOWN` (its barcodes went with it); in between: `REJECTED` "removed just now" | Reason | — | OK-untested |
| 3.12 | The bill thrown away after shelving (from the bill or its photo) | What was held goes into stock (it is on the shelves) | "Put into stock: 10" | KST aBillThrownAway… | OK-tested |
| 3.13 | Fewer arrived than the bill says | Line `shortBy` | "2 short" | — | OK-untested |

## 4. Unknown barcodes ("Needs a tap")

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 4.1 | Barcode nobody knows | `UNKNOWN`, `needsLabel` | Camera keeps the frame, sends it | KST anUnknownPacksLabel… | OK-tested |
| 4.2 | Label read; one clear product | `sure`, suggestion first | One tap "It's Parle-G 70 g" | KST | OK-tested |
| 4.3 | Another shop scans the same pack | Read reused: no photo, no AI call | Label shown | KST | OK-tested |
| 4.4 | Catalogue knows the barcode, the shop doesn't | `catalogue` (+`trusted`), no label | "Add Parle G 70 g (₹10)" | KST | OK-tested |
| 4.5 | Link to an existing product | Barcode linked, scans × pack into stock, `needsPhoto` when no photo | "Linked — take a front photo?" | KST | OK-tested |
| 4.6 | Link as a new product from the catalogue item | Product made with its name, barcode linked; two shops agree → trusted | — | KST | OK-tested |
| 4.7 | Linked twice (double tap) | 409 `UNKNOWN_DONE` | "Already linked" | KST | OK-tested |
| 4.8 | Not something the shop sells | `dismiss` 204 | Gone from the list | KST anUnknownBarcodeCanBeDismissed… | OK-tested |
| 4.9 | Another shop's unknown | 404 | — | KST | OK-tested |
| 4.10 | Label photo unreadable | Label `FAILED` with why; `needsLabel` again; the frame deleted | "Take a photo of the front" | — | OK-untested |
| 4.11 | Barcode already on another product of the shop | 409 `BARCODE_TAKEN` naming it | "Already on X — move it?" | (addBarcode) | OK-untested |
| 4.12 | Scanned with a bill, linked later: bill still a draft / added with this product / thrown away | Held with it / nothing (the bill brought it) / into stock; scans with and without a bill are separate entries | — | KST scansWithABillAreNeverCountedTwice… (added) | OK-tested (draft, thrown away OK-untested) |
| 4.13 | A barcode put away by mistake | `GET /scans/unknown?dismissed=true`, `POST …/reopen` (merged into an open entry of the same barcode and bill) | "Put away" list, "Bring back" | KST aBarcodePutAwayByMistake… | OK-tested |
| 4.14 | Another shop's read was of the wrong pack | This shop may send its own photo over it | "Wrong? Take a photo" | — | OK-untested |

## 5. The owner's corrections

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 5.1 | Barcode on the wrong product | `move-barcode` → `suggestedQuantity` | "Also move 6 pieces?" | KST theOwnerMoves… | OK-tested |
| 5.2 | Helper tries | 403 `OWNER_ONLY` | Hidden for helpers | KST | OK-tested |
| 5.3 | Move the stock; retry; more than it has | Moves once (`clientRef`); more than the first product's stock: 400 naming quantity; or `moveStock: true` on move-barcode does it in one go | Both counts | KST | OK-tested (one go OK-untested) |
| 5.4 | Pieces ↔ kg | 409 `UNITS_DIFFER` | "Set each one's count instead" | KST | OK-tested |
| 5.5 | Catalogue link was only this shop's | Catalogue follows | — | KST | OK-tested |
| 5.6 | Catalogue link trusted (other shops agree) | Review `CORRECTION`; shop's own link changes now | — | (CatalogueService.correct) | OK-untested |
| 5.7 | "I have 12" | `POST /products/{id}/stock` COUNT (round 1) | — | KIT | OK-tested |
| 5.8 | Unit change after a sale | 409 `PRODUCT_SOLD` (round 1) | — | KIT | OK-tested |
| 5.9 | Photo set / changed / removed | `PUT /photo`; the old one deleted only when nothing else uses it (another product with the same picture, the catalogue); a used file can't be deleted from the files screen (409) | Photo | KST aPhotoTwoProductsShare… | OK-tested |
| 5.10 | Cost by hand | `PUT /cost` owner | — | — | OK-untested |

## 6. The shared catalogue and the team

| # | Scenario | API answer | Screen (admin website) | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 6.1 | Two shops link a barcode to the same item | `confirmed` (trusted) | — | KST anUnknownPacksLabel… | OK-tested |
| 6.2 | A shop links it to another item | Review `CONFLICT`; the shop keeps its own | Review list | KST shopsThatDisagree… | OK-tested |
| 6.3 | Team decides | 204; `ADMIN` trusted; again 409 `REVIEW_DONE`; non-admin 403 | Decide button | KST | OK-tested |
| 6.4 | Product master CSV | Added trusted (`MASTER`), bad rows listed, clashes reviewed `IMPORT` | Result table | KST aProductMasterImport… | OK-tested |
| 6.5 | CSV over 5,000 rows / 2 MB | 400 / 413 `FILE_TOO_LARGE` (multipart upload) | "Split the file" | — | OK-untested |
| 6.5a | A product master row whose name only loosely matches a shop's link | Not confirmed: reviewed (`IMPORT`) | Review list | — | OK-untested |
| 6.6 | Scan statistics | Per day: scans, unknown rate, one-tap/picked/new | Chart | KST | OK-tested |
| 6.7 | A shop's photo shows its catalogue item | Shared photo (once) | — | (ScanService.labelRead) | OK-untested |

## 7. Counter suggestions

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 7.1 | Billing starts | `now`: sold most this hour, last 14 days | Chips under the search | KST theCounterSuggests… | OK-tested |
| 7.2 | Khata customer chosen | `usual` | "Ravi's usual" | KST | OK-tested |
| 7.3 | Items on the bill | `together`, not what is already on it | "Often with milk" | KST | OK-tested |
| 7.4 | Out of stock / removed / on the bill already | Left out | — | KST theCounterSuggests… | OK-tested |
| 7.5 | Redis down | Counted in the database; nothing breaks | — | — | OK-untested |
| 7.6 | Many counters at once | Counted at most once per 10 minutes per server for each list (Redis), bounded queries | — | — | OK-untested |
| 7.7 | Around midnight | "This hour" wraps (23:xx counts for 00:xx) | — | — | OK-untested |

## 8. Paper bills

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 8.1 | Photo of the paper bill | Draft with lines matched, `writtenOn` | Review like a bill | KST aPaperBillBecomes… | OK-tested |
| 8.2 | Confirm | A bill (stock down, khata, totals) with `writtenOn` and the photo | Bill | KST | OK-tested |
| 8.3 | Day more than 7 days back or in the future | 400 naming `writtenOn` | Inline | KST | OK-tested |
| 8.4 | Confirmed twice (even after midnight) | Same bill, `repeated` | — | KST | OK-tested |
| 8.5 | Confirm while still reading | 409 `READ_NOT_READY` | "Still reading" | — | OK-untested |
| 8.6 | The day's totals | Counted on the day it is confirmed (`createdAt`); the paper's day shown | "Written on 16 Nov" | — | OK-untested |
