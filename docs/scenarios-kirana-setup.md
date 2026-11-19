# Kirana product setup — scenario list (backend)

Everything a kirana owner or helper (and the field team) can run into while setting up the shop's products after
Submit: every product the shop sells, each with a count of what is on the shelf, from photographed pages of the shop's
own list or added one by one; "I've added all my products"; the field visit booked only then; approval only after it;
and online orders offering only what was counted. "Screen" says what the partner app must show (its round comes next).
Verdicts: **OK-tested**, **OK-untested**, **GAP**, **N/A-v1**, **INCONVENIENCE** (works, but annoying; with the fix).
Tests: `KST` = `KiranaStockIntegrationTest`, `FAT` = `FieldAgentIntegrationTest`, `KOT` = `KiranaOrdersIntegrationTest`,
`AIT` = `AdminIntegrationTest`.

Not in this round: the field agent's guided check of the list against the shelves and the spot check of about 10
products (admin round); the partner app's screens, guidance and languages (partner round).

## 1. Where the setup stands

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1.1 | Home page after Submit | `GET /kirana/setup`: products, uncounted (+ up to 20 `{id, name}` to open each), lines to check / added / skipped, pages reading / failed, pages left, each page (`pageNo`, photo, status, failure, `linesLeftOut`, its lines' counts), `canFinish`, `youCanFinish` (owner), `readyAt` | "Set up your products" card with progress, page by page | KST aPageOfTheShopsList… | OK-tested (fixed after reviews D, F) |
| 1.2 | App closed, reloaded, another phone | Everything is saved per line and per product: the same answer | Resumes where it stopped | KST (each call is a fresh request) | OK-tested |
| 1.3 | A helper looks | Same view; `youCanFinish` false (only the owner says it) | "Ask the owner to finish" | KST | OK-tested (fixed after review F) |
| 1.4 | A salon person calls it | 409 `NOT_A_KIRANA` (KiranaAccess) | — | (KiranaAccess, other tests) | OK-untested |

## 2. Pages of the shop's list read by AI

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 2.1 | Photo of one page | `POST /kirana/reads {kind: STOCK_LIST, fileIds: [one BILL photo]}` → 201 READING; read in the background into lines | "Reading your page…" | KST aPageOfTheShopsList… | OK-tested |
| 2.2 | Two pages in one upload | 400 naming `fileIds` (one page each, so a bad page is retaken alone) | "One page at a time" | KST aShopSendsAtMost60Pages… | OK-tested |
| 2.3 | Not a list (a selfie, a bill) | Read FAILED: "This doesn't look like a list of products…" | Retake | KST theSetupFinishesOnly… | OK-tested |
| 2.4 | Unreadable page | FAILED: "We couldn't read the products on this page…" | Retake, with tips | — | OK-untested |
| 2.5 | 60 pages kept | 409 `TOO_MANY_PAGES` (`max`); counted under a per-shop lock, so a burst can't pass 60; failed and deleted pages don't count | "Add the rest one by one" | KST aShopSendsAtMost60Pages… | OK-tested |
| 2.5b | Send a page, delete it, again and again | 100 pages a day at most, deleted and failed ones too (each was an AI read): 409 `TOO_MANY_PAGES_TODAY` | "Send more tomorrow" | KST aShopSendsAtMost100ListPagesADay… | OK-tested (fixed after reviews A, B) |
| 2.6 | A page thrown away | Its place is free again; its unchecked lines leave the list and the counts, deciding one is 404; added products stay | — | KST aPageDeletedTakes… | OK-tested (fixed after review E) |
| 2.7 | Same page sent twice (retry) | Same read (clientRef) even at the limit | — | KST same | OK-tested |
| 2.8 | Many pages on the first day | List pages don't use the shop's 30 daily AI reads (only the platform's daily cap) | — | — | OK-untested |
| 2.9 | AI key missing / AI down | Read fails with "enter it by hand" / retried as other reads | Add by hand | (ReadJob tests) | OK-untested |
| 2.10 | A page (or an Excel list) with more than 150 products | The first 150 are kept; the page's `linesLeftOut` says how many more | "150 read: photograph the rest again" | KST aPageDeletedTakes… | OK-tested (fixed after reviews B, F) |
| 2.11 | A list file too long or with a password | Fails with the reason in the list's words ("This list is too long…") | Retake | — | OK-untested |

## 3. Checking the lines

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 3.1 | Lines to check | `GET /kirana/setup/lines`: 20 at a time in the order pages were sent, each with its `pageNo` and up to 3 likely products (the shop's first, then the catalogue) and `sure` | One line at a time, with its suggestions | KST both setup tests | OK-tested |
| 3.1b | A line skipped by mistake, after closing the app | `?status=SKIPPED` lists them (and `ADDED`, with what was `counted`) | "Skipped (2)" | KST aPageOfTheShopsList… | OK-tested (fixed after reviews D, F) |
| 3.2 | Long list | `next` cursor; a cursor that doesn't parse starts again from the beginning | Keeps going | KST theSetupFinishesOnly… | OK-tested |
| 3.3 | Add as a new product | `POST /setup/lines/{id} {action: ADD, quantity}`: name from brand + name + size (or the wording), unit kg for loose goods else piece, price the MRP read; any of them may be given instead | "Add" with the count | KST aPageOfTheShopsList… | OK-tested |
| 3.4 | No count, or below 0 | 400 naming `quantity` ("Count how many are on the shelf (0 if none)") | Count field highlighted | KST | OK-tested |
| 3.5 | A product the shop already has | `{action: ADD, productId, quantity}`: its stock becomes the count (one COUNT move), no second product | Pick the suggestion | KST | OK-tested |
| 3.6 | Name already used | 409 `NAME_TAKEN` naming the product, so the app offers it | "You have this already" | KST | OK-tested |
| 3.7 | Add sent twice (lost answer, double tap) | The line as it is; nothing added or counted twice (the line is locked) | — | KST | OK-tested |
| 3.8 | Skip a heading / something not sold | `{action: SKIP}` | "Skip" | KST | OK-tested |
| 3.9 | Skipped by mistake | `{action: UNDO}` → back to check, with its suggestions | "Undo" | KST | OK-tested |
| 3.9b | The same product on two lines (shelf and godown) | 409 `ALREADY_COUNTED` (product, its stock) until `addToCount` true (add, to the stock read under the product's lock) or false (replace); the line keeps its own count | "Add 20 to the 5 counted, or replace?" | KST theSameProductOnTwoLines… | OK-tested (fixed after review F and the check) |
| 3.9c | Wrong product picked and counted | 409 `LINE_ADDED` on undo; the product is opened and recounted there | "Already added. To fix it, open the product" | KST | INCONVENIENCE (accepted: a recount fixes it) |
| 3.10 | Skip or undo an added line | 409 `LINE_ADDED` (`productId`: change the product itself) | Opens the product | KST | OK-tested |
| 3.11 | Another shop's line, or none | 404 `LINE_NOT_FOUND` | — | KST | OK-tested |
| 3.12 | No MRP on the line, or one no product may have (₹0, over ₹1 lakh) | 400 naming `pricePaise` (never a 500) | Price field asked | KST theSameProductOnTwoLines… | OK-tested (fixed after reviews B, E) |
| 3.14 | A removed product picked | 400 naming `productId` | — | — | OK-untested |
| 3.13 | Owner and helper on the same line at once | The line's row lock: one adds, the other sees it added | — | — | OK-untested |

## 4. "I've added all my products"

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 4.1 | No products | 409 `NO_PRODUCTS` | Guidance to start | KST, FAT | OK-tested |
| 4.2 | A page still reading, or lines left | 409 `LINES_TO_CHECK` (`lines`, `pagesReading`) | "Check the 25 left" | KST | OK-tested |
| 4.2b | A page that couldn't be read | 409 `PAGES_FAILED` (`pages`): retake or delete it, so no products are lost | The page with "Take again" | KST theSetupFinishesOnly… | OK-tested (fixed after review F) |
| 4.3 | A product never counted | 409 `PRODUCTS_NOT_COUNTED` (`uncounted`, up to 20 `products` `{id, name}`) | The list, each with "Count" | KST, FAT | OK-tested |
| 4.3b | A helper adds or undoes a line, or sends a page, while the owner finishes | Both take the shop's setup lock: the finish sees it | — | — | OK-untested (fixed after review A) |
| 4.4 | A removed product | Doesn't need a count | — | KST | OK-tested |
| 4.5 | A helper taps it | 403 `OWNER_ONLY` | Hidden for helpers | KST | OK-tested |
| 4.6 | Done | 200, `readyAt`; a note on the shop's timeline; the field visit booked with the agent for the pincode | "Done! The field agent will visit" | KST, FAT | OK-tested |
| 4.7 | Tapped twice / at the same moment | Same answer, one note, booked once (the setup lock, then the team lock and the shop's row) | — | KST (after it finished), FAT | OK-untested for the first finish at once |
| 4.8 | Finished before Submit (a draft) | Ready; booked when it is submitted | — | FAT aKiranaHeldByAnAgent… | OK-tested |
| 4.9 | Products added after finishing | Allowed (it's the shop's catalogue); not needed again | — | — | OK-untested |
| 4.10 | A helper adds a product with no count (or a sale takes one below zero) at the moment the owner finishes | The finish may go through with it uncounted; the agent's shelf check catches it, and it isn't offered online until counted | — | — | INCONVENIENCE (accepted: rare, harmless) |

## 4b. A picture for every product (photos checked by AI)

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 4b.1 | Which products need a photo | `GET /kirana/setup/photos`: products with no picture (none of the shop's, none in the shared catalogue), retakes first, then not sent yet, then being checked, by name, 20 a page; each with `sizeToShow` ("250 ml"), `loose`, its check (`photoCheck`, `code`, `reason`, `seen`) and `newPhotoUrl`. `GET /setup`: `photosMissing`, `photosChecking`, `photosRefused`, `photosLeftToday`, `nextStep` (ADD, LIST, COUNT, PHOTOS, WAIT, FINISH, DONE) | Home: the next step with one button. Photos: a guide with good and bad examples first, then one product after another, "Amul Taaza **250 ml**: make sure '250 ml' is visible" | KST aProductsPhotoIsChecked… | OK-tested (fixed after reviews D, F) |
| 4b.2 | A product in the shared catalogue with a picture | Needs no photo | Ticked | KST looseGoodsNeedNoLabel… | OK-tested |
| 4b.3 | Photo sent | `PUT /products/{id}/photo` → `photoCheck` CHECKING; nothing shows until it passes (the old picture stays); `newPhotoUrl` shows the photo being checked | "Checking…" with the photo | KST | OK-tested |
| 4b.3b | Finding out a check is done | The app reads `GET /setup` (one request) every few seconds while `photosChecking` > 0 | — | — | OK-untested |
| 4b.4 | Wrong size (500 ml for 250 ml) | REFUSED, `code` WRONG_SIZE, `seen` "500 ml": "This pack says 500 ml, but the product is Amul Taaza 250 ml. Take a photo of the 250 ml pack, or fix the size in the product's name if it is wrong." | Retake / Fix the name (owner) | KST, PhotoRulesTest | OK-tested |
| 4b.5 | Another product or a contradicting variant (toned vs full cream) | REFUSED, `code` OTHER_PRODUCT, `seen` what it showed | Retake / Fix the name | KST, PhotoRulesTest | OK-tested |
| 4b.5b | The shop left the variant out of its name ("Amul Taaza 250 ml" for Toned) | Accepted | — | KST, PhotoRulesTest | OK-tested |
| 4b.6 | People, two products, blurry, the back of the pack | REFUSED: PEOPLE, MANY, BLURRY, NOT_FRONT, each with its reason | Retake | KST | OK-tested |
| 4b.7 | Size not readable on the front | NEED_SIZE: "Take a photo of the side where 250 ml is printed"; the side alone is sent (`sizeFileId`, the front waiting is kept, even after the app was closed) and both are checked together; still unreadable: SIZE_UNREADABLE. The same photo as both: 400 naming `sizeFileId` | Second photo | KST, PhotoRulesTest | OK-tested (fixed after reviews D, F) |
| 4b.8 | Loose goods (by the kg) | The goods in their sack or bin: no front or size needed; never shared, even with a catalogue item | "Show the rice in its sack" | KST looseGoods…, PhotoRulesTest | OK-tested (fixed after review E) |
| 4b.9 | A photo still being checked when finishing | 409 `PHOTOS_NEEDED`: "3 products need a photo, and 2 photos are still being checked." / "…still being checked. Come back a little later." | — | KST | OK-tested (fixed after review F) |
| 4b.10 | Photos missing when finishing | 409 `PHOTOS_NEEDED` (`missing`, `checking`, `refused`) | "37 products need a photo" + Take photos | KST, FAT | OK-tested |
| 4b.11 | Changing a photo later | Same check; the accepted one shows until the new one passes; a refused one can be let go of (`fileId` null) keeping the accepted picture | "Change photo" / "Keep the old one" | KST | OK-tested (fixed after reviews D, F) |
| 4b.12 | Removing the shop's own photo | With a catalogue picture: removed, the catalogue's shows; without: 409 `PHOTO_NEEDED` | — | KST both photo tests | OK-tested (fixed after review E) |
| 4b.12b | The product renamed or its unit changed | Its photo is checked again against the new name (nothing shows meanwhile); a check started for the old name is dropped | "Checking…" | KST aProductsPhotoIsChecked… | OK-tested (fixed after reviews A, E) |
| 4b.12b2 | Renamed while removed | Its own photo is let go of: back, it needs a new photo | — | KST aRefusalOfAProductWithALongName… | OK-tested (fixed after the check) |
| 4b.12b3 | Renamed or changed past today's 300 checks | REFUSED `NOT_CHECKED`: "send it again after midnight" (a check again counts like any photo) | Retake tomorrow | — | OK-untested (fixed after the check) |
| 4b.12c | The product removed while its photo is checked | The photo waiting is let go of; put back, nothing stays "checking" | — | KST | OK-tested (fixed after reviews A, E) |
| 4b.12d | A late retry of an old request (same clientRef), even for a product removed since | Nothing changes; letting go of a photo names it (`dropFileId`), so a late retry never drops one sent since | — | KST | OK-tested (fixed after review A and the check) |
| 4b.12e | A product with a long name refused | Saved (the reason is cut to fit), never retried for ever | — | KST aRefusalOfAProductWithALongName… | OK-tested (fixed after the check) |
| 4b.13 | Photos sent again and again | 300 checks a day a shop (409 `TOO_MANY_PHOTOS_TODAY`, "after midnight", `leftToday`), counted under a lock | "Send more tomorrow" | KST aShopSendsAtMost300Photos… | OK-tested |
| 4b.13b | Many shops photographing at once | Photo checks have their own platform allowance (`app.kirana.photo-checks-per-day`, 3,000), so bills are never held up; past it, checks wait for the next day (still "checking") | — | — | OK-untested (fixed after reviews A, B) |
| 4b.14 | Shared catalogue | A checked photo of a pack matching its catalogue item (brand, name, size) becomes the item's picture when it has none, only while the shop is approved: at approval, then at each check (the shop's row read under a share lock, so an approval being saved isn't missed); a shop registering, suspended or sent back keeps its photos to itself | — | KST looseGoods… | OK-tested (fixed after reviews A, D, E) |
| 4b.15 | The AI can't read it, is switched off or down, or the day's allowance is used | Unreadable: REFUSED `UNREADABLE` "We couldn't see the product clearly…"; off, down or past the allowance: it stays "checking" and is checked later (never refused for our trouble) | Retake / "Checking…" | — | OK-untested (fixed after reviews E, F) |
| 4b.16 | Replaced and refused photos | Deleted once nothing points at them (a finished photo check holds nothing) | — | — | OK-untested (fixed after review A) |
| 4b.17 | A wrong picture in the shared catalogue | Reported by shops, reviewed by the team | "Wrong picture" | — | N/A-v1 (admin and partner rounds) |

## 5. The field team

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 5.1 | Submitted kirana setting up | In no group (not First visit), not on the dashboard, not assigned, not in an agent's load; its page says `waitingForVisit` false and `productsReadyAt` null | — | FAT aKiranaSettingUp… | OK-tested (fixed after review D) |
| 5.1b | Held by an agent from before (a salon sent back and made a kirana; V76) | Let go when submitted (logged); V76 lets go those waiting at deploy | — | FAT aKiranaHeldByAnAgent… | OK-tested (fixed after reviews B, D) |
| 5.2 | Approve before the setup is done | 409 `PRODUCTS_NOT_READY` | Button hidden (`waitingForVisit` false) | FAT | OK-tested |
| 5.2b | Reinstated while still setting up | The owner is told to finish adding products first | — | — | OK-untested (fixed after review B) |
| 5.3 | Setup done | First visit, assigned, approvable | — | FAT | OK-tested |
| 5.4 | A kirana approved before (a re-check, reinstated) | Never held back; finishing a setup then books nothing again | — | FAT aKiranaHeldByAnAgent… | OK-tested (fixed after review E) |
| 5.5 | Salons | Unchanged | — | FAT, AIT | OK-tested |
| 5.6 | The agent checks the list against the shelves | — | — | — | N/A-v1 (admin round) |

## 6. Counted products and online orders

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 6.1 | Added with a stock, or counted (even unchanged) | Counted | — | KST aProductIsCounted… | OK-tested |
| 6.2 | Added without a stock | Not counted (`counted` false on the product); not offered online | "Count it to sell online" | KST, KOT | OK-tested (fixed after review D) |
| 6.2b | A new product from a distributor's bill or a scan | Counted: it starts at 0 and what came in is added | — | — | OK-untested (fixed after reviews B, D, E) |
| 6.3 | Sold below zero at the counter | No longer counted (not offered online until counted), even if the bill is then cancelled | Shown in the setup's uncounted products | KST | OK-tested (decided: below zero means the count didn't hold) |
| 6.4 | Missing while packing an online order | No longer counted; offered again once counted | — | KOT aSupplierRejectingOnly… | OK-tested |
| 6.5 | Nearby shops' items | Only counted products | — | KOT (queries) | OK-untested |
| 6.6 | Products from before this version | Those with stock above 0 start counted (V76) | — | — | OK-untested |

## Many at once (load)

- A line is decided under its row lock (`FOR UPDATE`): retries and two phones add it once.
- List pages are counted under a per-shop advisory lock in the upload's transaction: a burst stops at 60.
- "I've added all my products" takes the team lock, then the shop's row (the order every assignment change takes):
  booked once, and never at the same time as a pincode change moves the shop.
- Lines are listed 20 at a time by index (`ix_kirana_list_lines_shop`); suggestions are matched in memory against the
  shop's products read once per request. The uncounted count and names use a partial index built CONCURRENTLY (V78).
- No AI call runs inside a transaction; reading a page is the existing background job.
