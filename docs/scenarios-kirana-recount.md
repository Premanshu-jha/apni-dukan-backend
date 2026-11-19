# Recount pages, products added after finishing, read failure codes — scenario list (backend)

Three things for the weeks between a kirana's Submit and its field visit, and for the app in the shop's language.
Tests: `KST` = `KiranaStockIntegrationTest`, `RFT` = `ReadFailureTest`. Verdicts as in the other scenario lists.

## 1. A page of the list sent as a recount (`recount: true` on `POST /kirana/reads`, STOCK_LIST only)

| # | Scenario | What happens | Test | Verdict |
| --- | --- | --- | --- | --- |
| 1.1 | A line clearly names one of the shop's products (a sure match, no other as likely) and has a count | The product's count becomes the list's, as the shop's Count would; the line is ADDED with `autoCounted` and `countBefore` | KST aRecountPage… | OK-tested |
| 1.2 | The line has no count written, or names nothing clearly, or a product the shop doesn't have | Comes up to check as usual; nothing changes | KST | OK-tested |
| 1.3 | Two lines of the page name the same product | Neither is taken: the shop says which count is right | KST | OK-tested |
| 1.4 | The count can't be the product's (half a piece) | The line comes up to check | KST | OK-tested |
| 1.5 | "Undo" on an auto-counted line | The count goes back to what it was, keeping sales and deliveries since (before + now − counted, never below 0); the line comes back to check | KST | OK-tested |
| 1.5a | Counted again since (by hand, even to the same number, or a later page), then Undo | That newer count stands; only the line comes back to check | KST aRecountNeverGuesses… | OK-tested |
| 1.5c | Two recounts (days apart) undone newest first | Both are put back: 20 → 15 → 10, and the product's last count is the one it had before each | KST twoRecountsUndone… | OK-tested |
| 1.5d | Its count was withdrawn since (missing for an online order), then Undo | Left uncounted: Undo never marks a product counted that nobody counted | — | OK-untested |
| 1.5b | The product's unit changed since | The count it has stands; the line comes back | — | OK-untested |
| 1.6 | Undo after the product was removed | The line comes back to check; nothing to put back | — | OK-untested |
| 1.7 | Undo on a line the shop added itself | 409 LINE_ADDED, as before (the product is changed instead) | KST | OK-tested |
| 1.8 | `recount: true` on a bill or a paper bill | 400 naming `recount` | KST | OK-tested |
| 1.8a | The line says kg and the product is sold by the piece (or the reverse) | Asked: never 25 kg read into 25 packets | KST aRecountNeverGuesses… | OK-tested |
| 1.8b | The product was never counted by the shop (added without a count, or sold below zero) | Asked: its first count is the shop's own, so a list's number never passes "finish" or approval by itself | KST | OK-tested |
| 1.8c | The product changed after the photo was sent (a sale, a delivery, an edit, a count, even confirming the same number) while the page waited to be read | Asked: the page's number is older than the shelf | KST | OK-tested |
| 1.8d | A second list page names a product counted from a list in the last 12 hours (the shelf's page, then the godown's) | Asked (add or replace: 409 ALREADY_COUNTED on Add) | KST | OK-tested |
| 1.8e | Add or Skip on an auto-counted line | 409 AUTO_COUNTED (both): undo it first, never a silent no-op | KST | OK-tested |
| 1.8j | Undo: did it put the count back? | The answer says `countPutBack` true, or false when a newer count (or a withdrawn count, a removed product, another unit) stands, so the app says which | KST | OK-tested |
| 1.8k | A shop with its 60 kept pages sends a recount | Accepted (recounts aren't kept pages); an ordinary page is still 409 TOO_MANY_PAGES | KST | OK-tested |
| 1.8l | Page numbers | A page's number is its place among all pages kept, hidden recount pages included: it changes only when an earlier page is thrown away, and a line of a hidden page still has its number | — | OK-untested |
| 1.8m | `readId` of a page thrown away, or another shop's | An empty list (200) | — | OK-untested |
| 1.8f | The recount page is thrown away | Its counts are put back first, as Undo on each (a newer count stands); then it is discarded | KST | OK-tested |
| 1.8g | Recount pages and the 60 kept pages | Not counted in the 60 (a shop recounts for years); the 100 pages a day still count them; shown in setup for a week, and however old while being read, failed (to retake or delete) or with lines to check | KST | OK-tested |
| 1.8h | Seeing what a recount changed | `GET /kirana/setup/lines?status=ADDED&readId=`: each line with `productName`, `countBefore`, `counted`, `stockNow` | KST | OK-tested |
| 1.8i | Lines to check from a recount sent after finishing | `nextStep` LIST again until they are answered | — | OK-untested |
| 1.9 | A page not sent as a recount | Never counts by itself | KST | OK-tested |
| 1.10 | INCONVENIENCE: a recount of 100 products asked 100 questions ("add or replace?") | Clear lines are taken without asking; the page shows `linesAutoCounted`, each with Undo | KST | OK-tested |
| 1.11 | A wrong match counted the wrong product | Only a sure match of the shop's own product is taken; the ADDED list shows each with its count before, and Undo | KST | OK-tested |

**Many at once.** The page is read once (the read is claimed by one job). Its lines are matched before the job's
transaction (one read of the shop's products per page, nothing locked meanwhile); the counts are then taken in the
transaction under the shop's setup lock (the one a decision on a line and "finish" take), with the products locked
together in id order, as bills lock them (no deadlock with a sale of the same products). A sale that landed after
the photo was sent leaves that product to be asked about.

## 2. Products added after "I've added all my products", before the first approval

| # | Scenario | What happens | Test | Verdict |
| --- | --- | --- | --- | --- |
| 2.1 | A product added after finishing has no count or no picture | The visit stays booked; setup says `beforeApproval` with `nextStep` COUNT, PHOTOS or WAIT | KST productsAddedAfter… | OK-tested |
| 2.2 | The agent approves meanwhile | 409 PRODUCTS_INCOMPLETE: "1 product has no count, 1 product has no photo. Ask the owner to finish in the app first." with up to 10 names | KST | OK-tested |
| 2.3 | The agent opens the shop | The API gives `kirana.productGaps` (counts, up to 10 names: "Maggi Noodles 70 g: count, photo"); the admin website shows the refusal's message with the counts, and will list the names when its kirana screens are built | KST | OK-tested |
| 2.4 | The owner counts and photographs them | `nextStep` DONE; approval goes through | KST | OK-tested |
| 2.5 | A shop approved before adds a product without a count or photo | Never held back (`beforeApproval` false; `productGaps` null); only counted products are offered online, as before | KST | OK-tested |
| 2.6 | A photo is still being checked by AI, or GS1 is still looking for its picture, when the agent approves | Refused, saying so: "1 photo is still being checked (try again in a few minutes)", not "ask the owner" (`checking` in the details and on the shop's page) | — | OK-untested |
| 2.6a | Every product removed after finishing | Refused: "The shop has no products in the app"; the owner's `nextStep` is ADD with `beforeApproval` | KST | OK-tested |
| 2.6b | A product sold below zero before the visit | Its count no longer holds: COUNT again before approval (the owner's home says so) | — | OK-untested |
| 2.7 | INCONVENIENCE: the agent arrives and finds products without photos | Told on the shop's page before travelling; the owner's home screen says what is left | KST | OK-tested |

## 3. Why a photo or file couldn't be read, as a code

| # | Scenario | What happens | Test | Verdict |
| --- | --- | --- | --- | --- |
| 3.1 | A read fails | `failureCode` beside `failure` (reads, list pages, paper bills, scanned labels): NOT_A_LIST, LIST_UNREADABLE, NOT_A_BILL, TOO_LARGE … | RFT | OK-tested |
| 3.2 | The same reason told for a list ("This list …") | The same code | RFT | OK-tested |
| 3.3 | A reason from an older version | OTHER: the app shows the English | RFT | OK-tested |
| 3.4 | The job and the views drift apart | One enum (`ReadFailure`) holds the words the job stores and gives their codes | RFT | OK-tested |
