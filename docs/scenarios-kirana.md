# Kirana counter — scenario list (round 1, backend)

Everything a kirana owner or helper can run into at the counter, with what the API answers and which test proves it.
The screens come with the partner app's kirana round; until then "Screen" says what the app must show.
Verdicts: **OK-tested**, **OK-untested**, **GAP**, **N/A-v1**, **INCONVENIENCE** (works, but annoying; with the fix).
Tests: `KIT` = `KiranaIntegrationTest`, `KRT` = `KiranaRulesTest`.

## 1. Who may use it

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1.1 | Salon owner/helper/barber calls any kirana endpoint | 409 `NOT_A_KIRANA` | Never shown a kirana screen (ShopGate) | KIT onlyAKiranasPeopleReachTheCounter | OK-tested |
| 1.2 | Signed in, no shop | 404 `NO_SHOP` | Start screen | same | OK-tested |
| 1.3 | Not signed in | 401 | Sign-in | same | OK-tested |
| 1.4 | Another kirana's product / bill / customer id | 404 `*_NOT_FOUND`; their product on my bill → 409 `PRODUCT_GONE` | "Not found" | KIT anotherShopsProductsBillsAndCustomersAreNotFound | OK-tested |
| 1.5 | Helper tries owner-only things (price, limit, UPI ID, unlinking a barcode, undoing a khata payment, more than ₹10 off, someone else's bill or his own after 10 minutes) | 403 `OWNER_ONLY` | "Only the owner can do this" (the bill ones say a helper can within 10 minutes) | KIT theOwnerChangesProducts…, aKiranaNeedsItsUpiId…, theOwnerCancelsAnyBill…, khataSalesStayWithinTheLimit…, aProductHasSeveralBarcodes…, aBillTakesLooseItems…, aBillsPaymentIsChanged…, moreThanOwedIsKept… | OK-tested |
| 1.6 | Helper removed mid-shift | Next call 404 `NO_SHOP` (membership read every call) | Signed out of the shop | (memberships tested in ShopIntegrationTest) | OK-untested here |
| 1.7 | Shop SUSPENDED or waiting for its visit | Counter keeps working (the shop's own tool, not the customer app) | — | — | OK-untested (by design) |

## 2. UPI ID and registration

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 2.1 | Kirana submits for visit without a UPI ID | 409 `REGISTRATION_INCOMPLETE`, `details.upiId` | UPI ID step highlighted | KIT aKiranaNeedsItsUpiId… | OK-tested |
| 2.2 | Owner types " SharmaStore@OKHDFCBank " | 200, stored `sharmastore@okhdfcbank` | Shows the cleaned ID | same; KRT upiIds… | OK-tested |
| 2.3 | Owner types "sharmastore" (no @bank) | 400 naming `upiId` | Inline error with an example | same | OK-tested |
| 2.4 | Helper opens settings | 200 with the UPI ID (he shows the QR) | QR at the counter | same | OK-tested |
| 2.5 | Typo in the UPI ID (valid form, wrong person) | Accepted; the field agent sees it on the shop page (`kirana.upiId`) and checks it at the visit | — | — | N/A-v1 (automatic name check with RazorpayX/Route in round 2) |
| 2.8 | Verified kirana's phone left signed in; someone changes the UPI ID | 403 `EDIT_LOCKED` without the code; the same ID again needs none | Code screen | KIT aVerifiedKiranasUpiIdChangesOnlyWithTheEditCode | OK-tested |
| 2.9 | Shop handed over | Old owner's UPI ID forgotten; products, bills, khata stay | New owner asked for his UPI ID | KIT handingTheShopOverForgetsTheOldOwnersUpiId… | OK-tested |
| 2.6 | Salon submits | Unchanged (services, hours) | Unchanged | Salon*IntegrationTest | OK-tested |
| 2.7 | Older app sends `udyamNumber` | Ignored, stored nothing, no re-check | Field no longer shown | ShopIntegrationTest, AdminIntegrationTest | OK-tested |

## 3. Catalogue

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 3.1 | Add a product with spaces in the name and barcode | 201, tidied | — | KIT productsAreAddedOnce… | OK-tested |
| 3.2 | Same name again (any case) | 409 `NAME_TAKEN` | "You already have …" with a link to it | same | OK-tested |
| 3.3 | Same barcode on another product | 409 `BARCODE_TAKEN` + `productId`, `name` | "This barcode is on <name>" with Open | same; aProductHasSeveralBarcodes… | OK-tested |
| 3.4 | Scan a known barcode (a single pack or a carton) | 200 `{product, pack}` | Adds `pack` pieces to the bill (12 for a carton of 12) | same; aProductHasSeveralBarcodes… | OK-tested |
| 3.5 | Scan an unknown or misread barcode | 404 `PRODUCT_NOT_FOUND` | "Link this barcode": pick a product (helper allowed, with its pack) or add new | same; theOwnerChangesProducts… (helper links) | OK-tested |
| 3.6 | Half a piece / 0.0001 kg | 400 naming the field | Inline error | same; stockComesIn…; KRT | OK-tested |
| 3.7 | Two people edit the same product | 409 `PRODUCT_CHANGED` | "Changed just now — reloaded" | theOwnerChangesProducts… | OK-tested |
| 3.8 | Remove a product, give its barcode or name to a new one, then restore the old one | Barcode: restored without it (it stays on the new one). Name: 409 `NAME_TAKEN` | Restored product shows its barcodes | theOwnerChangesProducts… (both cases) | OK-tested |
| 3.9 | Two saves of the same new name at the same moment | One 201, one 409 (unique key) | as 3.2 | — | OK-untested |
| 3.10 | List of 55 products; search; `%` and `_` typed | Pages of 50; `%`/`_` literal | Infinite scroll | theListComesInNameOrder… | OK-tested |
| 3.11 | Search "pure" for "100% Pure Ghee" (word inside the name) | Found (pg_trgm) | — | theListComesInNameOrder… | OK-tested (fixed this round) |
| 3.12 | Typed digits of a barcode that won't scan | Found by barcode start | — | productsAreAddedOnce… | OK-tested |
| 3.13 | One product, several barcodes (MRP batches, pack designs, a carton) | Up to 10 each, each with its `pack`; any of them scans; their digits search | Barcodes listed on the product | aProductHasSeveralBarcodes… | OK-tested (decided) |
| 3.14 | Same barcode linked again with the same pack / another pack | 200 unchanged / 409 `BARCODE_TAKEN` ("ask the owner to remove it first") | — | same | OK-tested |
| 3.15 | Half a piece as a pack; a pack without a barcode on a new product | 400 naming `pack` | Inline error | same | OK-tested |
| 3.16 | Owner unlinks a barcode; one it doesn't have | 200; 404 `BARCODE_NOT_FOUND` | — | same | OK-tested |
| 3.16b | Wrong pack linked (carton of 12 as 10) | Owner sends the code with the right pack: corrected; a helper gets 409 `BARCODE_TAKEN` (ask the owner) | — | same | OK-tested (fixed after review F) |
| 3.16c | Malformed barcode when linking | 400 naming `code` (the field sent) | That field | same | OK-tested (fixed after review F) |
| 3.16d | Restoring a product while its barcode is linked elsewhere at the same moment | Both wait on the barcode's advisory lock: no 500 | — | — | OK-untested (fixed after review A) |
| 3.18 | Piece chosen instead of kg | Owner changes the unit until its first sale; then 409 `PRODUCT_SOLD` | — | aProductsUnitIsChanged… | OK-tested (fixed after review F) |
| 3.17 | 11th barcode on one product | 409 `TOO_MANY_BARCODES` | "Remove an old one" | — | OK-untested |

## 4. Stock

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 4.1 | Stock came in / damaged / counted | New stock; a move with reason, note, who | Stock history on the product | KIT stockComesIn… | OK-tested |
| 4.2 | Count equal to the stock | 200, nothing recorded | — | same | OK-tested |
| 4.3 | Stock change on a removed product | 409 `PRODUCT_REMOVED` | "Put it back first" | — | OK-untested |
| 4.4 | Sale and restock at the same moment | Both kept (product row lock) | — | (lock shared with bills: manyBillsAtOnce…) | OK-untested |
| 4.5 | Restock retried after a lost answer | Stock comes in once (`clientRef`) | — | stockComesIn… | OK-tested (fixed this round) |
| 4.5b | The same `clientRef` sent for another product | 409 `STOCK_CHANGE_REPEATED`, nothing changed | — | — | OK-untested (fixed after review A) |
| 4.6 | Count above 9999 (big godown stock) | Allowed up to 999999 | — | KRT | OK-tested |
| 4.7 | 30-item delivery entered one by one | One call per product | — | — | INCONVENIENCE → round 3 (distributor bill upload) |

## 5. Walk-in bills

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 5.1 | Bill with pieces and kg, same product twice | 201, catalogue prices, stock down once per product | "Stock updated" before → after | KIT aBillUsesTheCatalogPrices… | OK-tested |
| 5.2 | Price changes after the bill | Old bill unchanged | — | same | OK-tested |
| 5.3 | Sell more than the app's stock | 201, stock below zero, `runningOut` | Orange "count again" hint | aBillCanTakeStockBelowZero… | OK-tested |
| 5.4 | Wrong quantity on line 2 | 400 `lines[1].quantity` | That line highlighted | aBillWithAWrongQuantity… | OK-tested |
| 5.5 | Item removed by the owner while the helper bills | 409 `PRODUCT_GONE` + ids, nothing billed | Those lines marked, "remove and save" | same | OK-tested |
| 5.6 | Double tap / retry after a lost answer | One bill; repeat → 200 `repeated: true` | Same success screen | theSameBillSentTwice… (8 at once) | OK-tested |
| 5.7 | Owner and helper bill together (10 at once) | Numbers 1–10, every sale counted | — | manyBillsAtOnce… | OK-tested |
| 5.8 | KHATA without a customer / customer on a cash bill / a stranger's id | 400 `khataCustomerId` / 404 | Customer picker required | aBillWithAWrongQuantity…, aBillsAnswerCarries… | OK-tested |
| 5.9 | UPI paid to the shop's QR but the customer's payment failed | App records what the shopkeeper says | Shopkeeper checks his UPI app before "Paid" | — | N/A-v1 (shop's own money) |
| 5.10 | Owner cancels a bill | Stock back, `VOID` moves; stays on the day it was made, its money handed back on the day of cancelling | "Cancelled" on the bill | theOwnerCancelsAnyBill…, theDaysTotals… | OK-tested |
| 5.11 | Cancel twice / helper cancels someone else's bill / after a return | 409 `BILL_VOIDED` / 403 / 409 `BILL_HAS_RETURNS` | — | same; itemsComeBackPartly… | OK-tested |
| 5.12 | Bill list | Newest first, 50 a page, 3 names preview | — | theBillListComesNewestFirst… | OK-tested |
| 5.14 | UPI bill: the QR for the total | `upi.uri` ready to draw | QR with amount | aBillsAnswerCarries… | OK-tested |
| 5.15 | Khata bill: tell the customer their new total | `khata.balancePaise`, `availablePaise` | "Now ₹X on khata" | same | OK-tested |
| 5.16 | Repeat of a bill shows what the first one did | Same stock before → after | Same screen | same | OK-tested |
| 5.17 | Loose item by amount ("₹20 of jeera") | Weight worked out to the gram, amount kept as asked | Amount keypad on kg items | aBillTakesLooseItems… | OK-tested (decided) |
| 5.17b | By amount on a piece item; less than a gram; both quantity and amount | 400 naming `lines[i].amountPaise` / `.quantity` | Inline error on that line | same | OK-tested |
| 5.17c | Item not in the list (name, price each, how many) | Line without a product; no stock change; one packet of three can come back | "Other item" button | same | OK-tested (decided; quantity after review F) |
| 5.17e | A listed product typed as an item not in the list | 409 `ITEM_IN_LIST` + `productId` (its price and stock would be skipped) | "It's in your list" with Pick | same | OK-tested (fixed after review B) |
| 5.17f | ₹20 at ₹450/kg (not whole grams), returned in two parts with a bill discount | 0.044 kg; refunds add up exactly to the total | — | aLooseItemByAnAmountThatIsntWholeGrams… | OK-tested |
| 5.17d | Round-off by a helper (≤ ₹10); more by a helper; more than the bill | 201 / 403 `OWNER_ONLY` / 400 `discountPaise` | Discount field | same | OK-tested (decided) |
| 5.18 | Part paid now, rest on khata | Only the rest goes on khata; QR for the part paid by UPI | "Paid now" field on khata bills | aKhataBillCanBePartPaidNow… | OK-tested (decided) |
| 5.18b | Paid now ≥ the total; paid now on a cash bill; without saying cash or UPI | 400 `paidNowPaise` / `paidNowBy` | — | same | OK-tested |
| 5.18c | UPI failed, they paid cash (or moved to khata, or to another customer's khata) | `POST /bills/{id}/payment`: same bill number, khata moved with `CHANGE` entries, kept in `changes` | "Change payment" on the bill | aBillsPaymentIsChanged… | OK-tested (decided) |
| 5.18d | Payment change over the new customer's limit | 409 `KHATA_LIMIT`, nothing moved (both balances checked) | — | same | OK-tested |
| 5.18e | Same payment change twice (a retry with the old version) | 200, nothing new | — | same | OK-tested |
| 5.18f | Owner and helper change the same bill from two phones | The second: 409 `BILL_CHANGED` | "Changed just now — reloaded" | same | OK-tested (fixed after review E) |
| 5.18g | More paid now on the same customer (owes less) | Allowed even if the limit was lowered since | — | same | OK-tested (fixed after review B) |
| 5.18h | A khata bill already paid off, changed to cash | 409 `KHATA_ALREADY_PAID` + `advanceAfterPaise`; with `advance: true` it is kept as their advance | "Their payments already cover it" | takingAPaidKhataBillOff… | OK-tested (fixed after review B) |
| 5.18i | Payment change on yesterday's bill | Counts today (moved into cash, out of UPI); yesterday's figures stay | — | aPaymentChangedOrABillCancelledToday… | OK-tested (fixed after reviews A, B, E, F) |
| 5.19 | Customer returns one item, or part of a kg line | `POST /bills/{id}/returns`: stock back, refund = the line's share after the bill's discount, the way the bill was paid | "Return items" on the bill, refund amount shown | itemsComeBackPartly… | OK-tested (decided) |
| 5.19b | Last return of a bill | Exactly what is left of the total | — | same | OK-tested |
| 5.19c | More than is left / a line twice / no such line | 400 naming `lines[i]...` | — | same | OK-tested |
| 5.19d | Return on a part-paid khata bill | Off the khata first (up to what the bill still has on it), the rest handed back as paid now: `offKhataPaise`, `handBackPaise`, `handBackBy` | "Hand back ₹50 cash" | aReturnOnAPartPaidKhataBill… | OK-tested (fixed after reviews B, F) |
| 5.19g | Return on a cancelled bill | 409 `BILL_VOIDED` | — | aHelperCancelsOrTakesAReturn… | OK-tested |
| 5.19h | Cancel a part-paid khata bill | Paid-now part counted as handed back that day, khata part off | — | cancellingAPartPaidKhataBill… | OK-tested |
| 5.19e | Same return 8 times at once | One return; repeats → 200 `repeated` | — | theSameReturnSentEightTimesAtOnce… | OK-tested |
| 5.19f | Cancel or change payment after a return | 409 `BILL_HAS_RETURNS` | "Return the rest instead" | itemsComeBackPartly… | OK-tested |
| 5.13 | Helper made a mistake on his own bill | Cancel / return / change payment within 10 minutes; after that, or on others' bills, 403 `OWNER_ONLY` | — | aBillsPaymentIsChanged…, aHelperCancelsOrTakesAReturn… | OK-tested (decided) |
| 5.20 | UPI bill before the owner set the UPI ID | `upi` null, `upiIdMissing: true` | "Ask the owner to set the UPI ID" | — | OK-untested (fixed after review F) |
| 5.21 | A wrong discount noticed after saving | Cancel and bill again (new number) | — | — | INCONVENIENCE → kept: a discount change is rare and should leave a trace |

## 6. Khata book

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 6.1 | Add customer with phone and limit (owner) | 201 | — | KIT khataSalesStayWithinTheLimit… | OK-tested |
| 6.2 | Helper sets a limit or "no limit" | 403 `OWNER_ONLY` | Limit field hidden for helpers | same; aNewCustomerStarts… | OK-tested |
| 6.3 | Same phone twice | 409 `PHONE_TAKEN` + customer | "Already in your khata as …" | same | OK-tested |
| 6.4 | Khata bill over the limit | 409 `KHATA_LIMIT` + `availablePaise`, nothing billed | "Can buy ₹70 more; take the rest now" | same | OK-tested |
| 6.5 | Payment more than owed | 409 `MORE_THAN_OWED` + `balancePaise`, `advancePaise`; again with `advance: true` keeps the extra as an advance | "They owe ₹175. Keep ₹25 as advance?" | same; moreThanOwedIsKept… | OK-tested (decided) |
| 6.6 | Payment double tap (8 at once) | One entry; repeats → 200 `repeated` | — | theSamePaymentSentEightTimesAtOnce… | OK-tested |
| 6.12 | Phone pasted as "+91 98765-43210" | Kept as 10 digits | — | phonesTypedAsPeopleDo… | OK-tested (fixed this round) |
| 6.13 | Find a customer by flat (note) or phone digits | Found | — | same | OK-tested (fixed this round) |
| 6.14 | New customer added by anyone | ₹2,000 limit unless the owner sets another or none | Limit shown on the customer | aNewCustomerStarts… | OK-tested (decided) |
| 6.15 | Advance used up by the next khata bill | Balance goes from -₹25 up | — | moreThanOwedIsKept… | OK-tested (decided) |
| 6.7 | Owed list and total | `owing=true`, `owedPaise` | Khata tab total | same | OK-tested |
| 6.8 | Cancel a khata bill already paid | Balance below zero: their advance | "₹350 advance" | cancellingAKhataBill… | OK-tested |
| 6.8b | …after they were taken out of the book | Back in the book (phone dropped if now someone else's) | — | cancellingAPaidBillOfSomeoneTakenOut… | OK-tested (fixed this round) |
| 6.9 | Take out of the book while owing | 409 `STILL_OWES` | "Record their payment first" | same | OK-tested |
| 6.10 | WhatsApp reminder | — (app opens WhatsApp with the amount) | Remind button | — | N/A-v1 backend |
| 6.11 | Payment recorded on the wrong customer | Owner: `POST /khata/{id}/payments/{entryId}/reverse` → `REVERSAL` entry, the payment marked `reversed`; twice → 409 `PAYMENT_REVERSED`; not a payment → 404 `KHATA_ENTRY_NOT_FOUND` | "Undo" on a payment (owner) | moreThanOwedIsKept… | OK-tested (decided) |
| 6.11b | Undo a payment of someone taken out of the book | Back in the book, owing again | — | — | OK-untested |
| 6.11c | Helper typed ₹500 for ₹50 | He undoes his own payment within 10 minutes; someone else's: 403 | — | moreThanOwedIsKept… | OK-tested (fixed after review F) |
| 6.16 | Customer leaving with an advance | Owner gives it back (`advance-back`), then takes them out of the book | "Give back ₹300" | theOwnerGivesAnAdvanceBack… | OK-tested (fixed after review F) |
| 6.17 | Advance shown plainly | `owesPaise`, `advancePaise` on each customer; `advancesPaise` for the book | — | same | OK-tested (fixed after review F) |
| 6.18 | Re-adding a customer while a cancel puts back someone with that phone | Both wait on the phone's advisory lock: no 500 | — | — | OK-untested (fixed after review A) |

## 7. Day totals

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 7.1 | Day's sales by cash / UPI / khata; khata received cash and UPI apart; cash taken; cancelled that day | `GET /kirana/day` | Today screen, evening cash count | theDaysTotals…, khataSalesStayWithinTheLimit… | OK-tested |
| 7.1b | Part paid now, payment changed, returns, a payment undone the same day | Paid-now part in cash/UPI, the rest in khata; a bill as paid when made, each change on its own day; returns by way given back; an undone payment off the khata received; `cashInPaise`/`upiInPaise` net | Today screen | aKhataBillCanBePartPaidNow…, aBillsPaymentIsChanged…, itemsComeBackPartly…, moreThanOwedIsKept… | OK-tested |
| 7.1c | Yesterday's bill cancelled or changed today | Yesterday's figures stay as counted; today hands back / moves the money | — | aPaymentChangedOrABillCancelledToday…, theDaysTotals… | OK-tested (fixed after reviews A, B, E, F) |
| 7.5 | Discounts given that day | `discountPaise` | — | — | OK-untested |
| 7.2 | Day boundary at midnight India time (23:59 vs 00:01) | India day (Asia/Kolkata) | — | theDaysTotals… | OK-tested |
| 7.3 | A date far away | 400 `date` | — | same | OK-tested |
| 7.4 | "How much did each helper take?" | Not split by person yet | — | — | INCONVENIENCE → partner-app round |

## Many at once (load)

- A shop's bills are serialized on its settings row: two counters wait milliseconds; different shops never wait on
  each other. Products locked in id order, customer last: no deadlock between bills and cancellations.
- Every list is a 50-row page read by index; searches use pg_trgm GIN indexes; no index mentions stock, so a sale
  updates the product row in place.
- One bill = a fixed number of statements (lines and moves in one batch each), not one per item.
- A bill's later changes (cancel, return, payment change) lock the bill's row first, then its products in id order,
  then khata customers in id order: the same return or change sent twice waits and then sees the first.
- Product pages read their barcodes in one query for the page; a scan is one lookup on the unique (shop, code) key.
