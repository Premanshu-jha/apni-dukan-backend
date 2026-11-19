# GS1 DataKart — scenario list (backend)

GS1 India's DataKart (the national product registry) gives the shared catalogue products' official pictures and
finds barcodes for products added without one. **Off until `GS1_DATAKART_URL` and `GS1_DATAKART_KEY` are set**; what
appears meanwhile is queued and looked up once they are. Written before access to DataKart's API documentation: the
adapter (`DataKart`) is adjusted to it when access comes. Verdicts as in the other scenario lists.
Tests: `G1T` = `Gs1IntegrationTest`.

## 1. Switched on by its settings

| # | Scenario | What happens | Test | Verdict |
| --- | --- | --- | --- | --- |
| 1.1 | Settings empty (or only one set) | The job does nothing; no call to GS1; lookups are still queued | G1T withoutItsSettings… | OK-tested |
| 1.1a | Variables set to a placeholder (`NULL`, `NONE`, `OFF`, any case) or blank | Off, as if not set: nothing called, nothing shown as "finding" | DataKartTest aPlaceholderValue… | OK-tested |
| 1.2 | Both set | The job runs every minute on one server, 10 lookups a run | G1T | OK-tested |
| 1.2a | The key is wrong (401/403) or the URL isn't a web address (no https://, another scheme) | The run stops at the first call; the lookups taken are given back as they were (the try not counted), tried again in 15 minutes; an error is logged | G1T aWrongKeyStops…, DataKartTest | OK-tested |
| 1.3 | DataKart down, busy (408/429), answers an error or can't be read | Tried again 30 minutes later; after a day of that, NOT_FOUND (again a month later). The shop isn't kept waiting: after 3 failures (or a day) the product counts as a photo to take | G1T gs1NotAnswering…, aBusyDataKart… | OK-tested |
| 1.3a | Another failure (a bug) for one lookup | After 8 in a row, REFUSED with the error's name; `tries` restarts after any answer | — | OK-untested |

## 2. A catalogue item's official picture, by barcode (CODE)

| # | Scenario | What happens | Test | Verdict |
| --- | --- | --- | --- | --- |
| 2.1 | A barcode new to the catalogue, its item without a picture | Queued (CatalogueStore.addCode) | G1T | OK-tested |
| 2.2 | GS1 knows it, the same product (brand, name, size agree), its picture shows it (AI photo check) | The item's picture (stored as the platform's, no shop's), for every shop selling it; its brand and size filled in; setup's `photosMissing` drops | G1T aCatalogueItemGets… | OK-tested |
| 2.3 | GS1's product is another size or a contradicting variant | REFUSED (note: what GS1 says); no picture fetched, no AI call | G1T gs1sProductOrPicture… | OK-tested |
| 2.4 | GS1's picture shows something else | REFUSED; the stored picture thrown away | G1T | OK-tested |
| 2.5 | GS1's picture doesn't show the size | Accepted: GS1's size agreed with ours | — | OK-untested |
| 2.6 | A picture link on a host DataKart may not send us to (or not https, or a redirect) | Not fetched | G1T | OK-tested |
| 2.7 | Not known to GS1, or no usable picture | NOT_FOUND, looked up again a month later | G1T | OK-tested |
| 2.8 | A shop's checked photo became the item's picture meanwhile | Nothing fetched; a picture fetched at that moment is thrown away | — | OK-untested |
| 2.9 | The day's GS1 picture checks used (`GS1_CHECKS_PER_DAY`, apart from shops' own photos) | The lookup waits for tomorrow; shops' photos are never held up | G1T theDaysGs1Checks… | OK-tested |
| 2.9a | The AI can't make a picture out | That picture is thrown away and the next tried; none left: NOT_FOUND (a month), never every minute | G1T aPictureTheAiCant…, anUnreadablePicture… | OK-tested |
| 2.9b | GS1 gives a GTIN-14 (leading zeros) | Read as the EAN-13 (or EAN-8) the pack prints and a scan reads | DataKartTest | OK-tested |
| 2.9c | The catalogue item is removed or merged by a review | Its lookup goes with it (cascade), or follows the item it was merged into | G1T aCatalogueItemRemoved… | OK-tested |
| 2.10 | Another shop adds the same pack later | Its picture shows at once; nothing looked up again | G1T | OK-tested |

## 3. A product added without a barcode: its barcode by name (NAME)

| # | Scenario | What happens | Test | Verdict |
| --- | --- | --- | --- | --- |
| 3.1 | Packed product added without a barcode, its size in its name | Queued | G1T | OK-tested |
| 3.1a | Added with a barcode, loose (by kg), or no size in its name | Not queued (can't be matched clearly) | G1T aMatchByBarcode… | OK-tested |
| 3.1b | Waiting for GS1 | Product `findingPicture`; setup `photosFinding`, listed last in photos to take; only those left: `nextStep` WAIT (the shop may photograph anyway) | G1T aProductAddedWithout… | OK-tested |
| 3.2 | One clear match (same size, no contradicting variant, a sure score, no second as likely) | Its barcode joins the catalogue (from GS1: trusted), the product is linked to that item, its picture follows (2.2) | G1T aProductAddedWithout… | OK-tested |
| 3.3 | Two as likely (GS1 listing the same barcode twice doesn't hide the other), or none sure | NOT_FOUND (never a guess), again a month later | G1T | OK-tested |
| 3.4 | The product's own barcode | Never set by GS1: the product shows `suggestedBarcode` ("Is this its barcode? Tap to add"), and its first scan offers the product in one tap | G1T | OK-tested |
| 3.4a | The match is wrong: "Not this product" (owner) | Link, picture and suggestion go; REFUSED, never looked for again under that name; again: 409 NOT_MATCHED; with a barcode: 409 HAS_BARCODE | G1T | OK-tested |
| 3.4b | Renamed (or unit changed) without a barcode | What GS1 matched is let go of; looked for afresh under the new name (or not at all without a size). A catalogue item the shop linked itself (a scan, a read list) stays, and "Not this product" says NOT_MATCHED | G1T, aProductTheShopLinked… | OK-tested |
| 3.4c | Renamed or "Not this product" while GS1 is being asked | The product is locked, then the lookup: GS1's answer for the old name is dropped; a refused lookup stays refused | — | OK-untested |
| 3.4d | Admin: lookups by status with counts, 50 a page | Super admins only (403 otherwise); 400 for another status; the next page continues without repeats | G1T theAdminSees… | OK-tested |
| 3.5 | The product got a barcode or a catalogue item meanwhile, or was removed | Nothing to do | — | OK-untested |
| 3.6 | DataKart can't search by name | No match found; pictures by barcode still work | — | OK-untested |

## Many at once

- One server runs the job at a time (job lease); rows are claimed with `FOR UPDATE SKIP LOCKED` and held 30 minutes.
- No call to GS1 or the AI inside a transaction; pictures are at most 10 MB, cleaned by the photo cleaner (a few at a
  time per server).
- GS1's picture checks use their own allowance (`GS1_CHECKS_PER_DAY`), never shops' photo checks or the bills' AI.
- Rows are held 30 minutes when claimed (longer than a run of 10), so two servers never look up the same row.
- Setup's counts and the admin list use indexes (`ix_kirana_gs1_status`, `ix_kirana_gs1_item`, the unique keys).
