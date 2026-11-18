# Kirana online orders — scenario list (round 2, backend)

Everything a customer, a kirana owner or helper, a nearby shop and the Clozest team can run into with online orders,
with what the API answers and which test proves it. The screens come with the apps' kirana rounds; until then "Screen"
says what the app must show.
Verdicts: **OK-tested**, **OK-untested**, **GAP**, **N/A-v1**, **INCONVENIENCE** (works, but annoying; with the fix).
Tests: `KOT` = `KiranaOrdersIntegrationTest`, `FCT` = `FssaiCheckJobTest`, `KIT` = `KiranaIntegrationTest`.

## 1. A shop gets ready to take orders

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1.1 | New kirana opens "Online orders" | 200 `missing: [APPROVAL, UPI_ID, FSSAI, SWITCHED_OFF]` | A checklist, each item with where to fix it | KOT aShopTakesOrdersOnlyOnce… | OK-tested |
| 1.2 | Owner turns orders on before he is ready | 409 `NOT_READY`, `details.missing` | "Orders start once …" with the missing steps | same | OK-tested |
| 1.3 | Helper turns orders on/off or adds the licence | 403 `OWNER_ONLY` | "Only the owner can do this" | same | OK-tested |
| 1.4 | FSSAI number with spaces ("1362 4013 0001 11") | 200, stored as 14 digits | Shows the cleaned number | same (200 and CHECKING) | OK-tested |
| 1.5 | Number not 14 digits | 400 naming `number` | Inline error | same | OK-tested |
| 1.6 | Certificate file of another shop, or not a registration document | 400 naming `fileId` | "Upload your own certificate" | same (another shop's) | OK-tested (other purpose OK-untested) |
| 1.7 | Certificate added: AI reads it within a minute | `fssai.status` CHECKING → VERIFIED / REJECTED with a reason; owner notified (`KIRANA_FSSAI`, queued with the decision) | Status chip; the reason in full | FCT (rules); the job's AI call OK-untested in CI | OK-tested (rules) |
| 1.8 | AI: unreadable photo, not an FSSAI document, no number, a different number, expired ("31 Mar 2026"), no last day readable, another business's name | REJECTED with the reason in plain words | Reason + "Upload again" | FCT unreadableOtherDocuments… | OK-tested |
| 1.9 | Business name differs but the operator is the owner | VERIFIED (owner's name counts) | — | FCT aClearCertificateOfThisShop… | OK-tested |
| 1.10 | Usual shop words only in common ("General Store") | Not a match | Reason shown | FCT | OK-tested |
| 1.11 | AI key not set, AI down, or the day's AI calls used up (500/day) | Stays CHECKING; tried again later (5 tries), then waits for a person: the owner is told, it shows in `GET /admin/kirana/fssai`, its reason says "Our team is checking it" | "Checking — usually within a day" | — | OK-untested |
| 1.11a | Owner sends the same certificate again after a refusal | CHECKING for a person (the AI would say the same) | "Our team is checking it" | — | OK-untested |
| 1.12 | Super admin decides by hand | 200 `VERIFIED` with valid-until (400 naming `validUntil` without it), or REJECTED with a reason; owner told; others 403 | Admin website | KOT aShopTakesOrdersOnlyOnce… (verify) | OK-tested (reject OK-untested) |
| 1.13 | Licence expires while orders are on | `missing` shows FSSAI again; the shop drops out of `nearby` (same READY rule) | Banner "Your FSSAI licence expired" | same (`missing`) | OK-tested |
| 1.13a | Owner uploads the renewed licence (the same number) while the old one is valid; the new one is being checked or refused | The shop keeps taking orders until the old one's last day (`fssai.currentValidUntil`; the reason and the refusal notice say so; the old certificate stays on record); then FSSAI is missing; verified: replaces it | "Your current licence keeps your shop open until …" | KOT aRenewedLicence… | OK-tested |
| 1.13b | Owner uploads a different licence number, or the team refuses with `endCurrent` (cancelled, not genuine) | Nothing kept: orders stop until it is verified | "Your FSSAI licence is being checked" | KOT anotherLicenceNumberOr… | OK-tested |
| 1.13c | The shop is handed over | The licence the old owner added is forgotten (his, as the food business operator), the switch off; the new owner adds his own | FSSAI step shown again | — | OK-untested |
| 1.14 | Owner uploads a new certificate, or corrects the number on the same one, while it is being read | The read's result is kept only for the same file and number; the new one is read | — | (OnlineStore.fssaiResult guard) | OK-untested |

## 2. The shared catalogue

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 2.1 | First shop adds a product with a barcode | A catalogue item is made from it, linked to the product | — | KOT theFirstShopToAddABarcode… | OK-tested |
| 2.2 | Another shop scans the same barcode | 200 name, brand, size, MRP, pack prefilled | Form prefilled; owner types only price and stock | same | OK-tested |
| 2.3 | Barcode nobody has | 404 `CATALOGUE_NOT_FOUND` | Empty form | same | OK-tested |
| 2.4 | A second barcode (a 6-pack) linked to a product | Added to the same item with its pack | — | same | OK-tested |
| 2.5 | Two shops add the same new barcode at once | One item; the second links to it (advisory lock + ON CONFLICT) | — | — | OK-untested |
| 2.6 | Super admin corrects a name | 200; every shop's scan shows the new name; stale version 409 `CATALOGUE_CHANGED`; non-admin 403 | Admin website | same | OK-tested |
| 2.7 | Shop's own name for a product differs from the catalogue | The shop's name is what its customers see; nearby items show the catalogue's; order lines from a nearby shop show that shop's own name | — | — | OK-untested |

## 3. The customer finds shops and items

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 3.1 | Customer opens the app | Ready shops within 2 km, nearest first, 50 a page | Shop list with distance and rating | KOT theCustomerSeesReadyShops… (order) | OK-tested |
| 3.2 | A shop switches orders off | Gone from the list; its pages 409 `SHOP_NOT_TAKING_ORDERS` | "This shop isn't taking orders now" | same | OK-tested |
| 3.3 | Shop's products | In-stock and out-of-stock (greyed), name order, search by prefix | Product list | same (names) | OK-tested (stock flag, search OK-untested) |
| 3.4 | Items the shop doesn't have but shops within 1 km of it do | `nearby-items`, the lowest price near ("from"; the quote may pick a shop covering more) | "Also from shops near Sharma General Store" | same | OK-tested |
| 3.5 | Customer moves more than 2 km away mid-cart | Quote 409 `TOO_FAR` | "This shop doesn't deliver here" | — | OK-untested |

## 4. Quote and plans

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 4.1 | Shop has everything | `combined` only, `choose: false`, total = items + ₹10 | One total | KOT (single-shop orders, by their total) | OK-tested |
| 4.2 | Shop short of a quantity | The rest from a shop within 1 km of it, combined only | Lines marked "from a nearby shop" | KOT aCombinedOrder… | OK-tested |
| 4.3 | Shop lacks a whole item | `choose: true`: combined (one delivery) or separate (the other shop delivers) | Two options with totals | same, aSeparateOrder… | OK-tested |
| 4.4 | Nobody within reach has it | Listed in `unavailable` (per plan) | "Not available near you" | aSeparateOrder… | OK-tested |
| 4.5 | More than 2 other shops would be needed (suppliers and separate deliveries together) | Lines beyond 2 shops are unavailable | Same | — | OK-untested |
| 4.5a | Two cart lines for the same item | A shop's stock is promised once across both | — | — | OK-untested |
| 4.6 | Price or stock changed between quote and pay | 409 `PRICE_CHANGED` with the new quote | "Prices changed" with the new totals | KOT aCombinedOrder… | OK-tested |
| 4.7 | Nothing in the cart can be given | Quote 200 with `combined: null`; checkout 409 `NOTHING_AVAILABLE` | "None of these can be ordered now" | — | OK-untested |
| 4.8 | A product deleted mid-cart | 409 `PRODUCT_GONE` naming the line | Line removed | — | OK-untested |

## 5. Checkout and payment

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 5.1 | Customer pays | Checkout PENDING with a Razorpay order → Razorpay checkout → `/paid` with the signature → PAID; orders PLACED, shops notified | Razorpay sheet, then the order screen | KOT aCombinedOrder… | OK-tested |
| 5.2 | Forged or wrong signature | 400 `BAD_SIGNATURE` | "Payment couldn't be confirmed" (webhook still settles it) | same | OK-tested |
| 5.3 | Pay tapped many times / sent again by the app | One checkout per `clientRef` | — | KOT manyTapsAtOnce… | OK-tested |
| 5.4 | App closed after paying | Webhook `payment.captured` marks it PAID; repeat event DUPLICATE, other event OLD | Order appears in "My orders" | KOT theWebhookMarks… | OK-tested |
| 5.5 | Webhook not signed by Razorpay | 403 | — | same | OK-tested |
| 5.6 | Not paid within 30 minutes | Checkout EXPIRED 5 minutes later (a last-moment payment still counts), orders cancelled | "Payment time ran out" | same | OK-tested |
| 5.7 | Paid after it expired | Full refund `NOT_PAID_IN_TIME` | "Your money is on its way back" | same | OK-tested |
| 5.8 | Razorpay keys not set / Razorpay down | 503 `PAYMENTS_OFF` / `PAYMENTS_UNAVAILABLE` (the checkout is kept; sending it again asks Razorpay again) | "Online orders are paused" / "Try again" | — | OK-untested |
| 5.10 | Customer pays the same checkout twice (another UPI app) | The second payment refunded in full once (`PAID_TWICE`), shown in the checkout's refunds | "₹37 paid twice is on its way back" | KOT aSecondPaymentOfTheSameCheckout… | OK-tested |
| 5.11 | The shop stops taking orders between checkout and payment | No order reaches it; refunded in full (the ₹10 too) | "The shop stopped taking orders: refunded" | KOT aShopThatStoppedTakingOrders… | OK-tested |
| 5.12 | A subscription payment reaches the shared webhook | Not a kirana checkout: left to the subscriptions | — | (SubscriptionIntegrationTest) | OK-tested |
| 5.9 | Pay Later chosen | Same flow; method recorded | — | — | OK-untested |

## 6. The shop handles the order

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 6.1 | New order | WAITING with `secondsLeft` (3 minutes) | Ringing card with Accept / Reject | KOT | OK-tested |
| 6.2 | Accept | ACCEPTED; stock goes down (ORDER move) | Packing list | KOT aCombinedOrder… | OK-tested |
| 6.3 | Accept tapped by owner and helper at once | One wins, stock down once | — | KOT manyTapsAtOnce… | OK-tested |
| 6.4 | Not accepted in 3 minutes | TIMED_OUT; own part cancels the order; refunded (the ₹10 when no other order of the checkout goes on); `accept` → 409 `TOO_LATE` until the job runs, `WRONG_STATUS` after | "Order missed" | KOT aPartNotAccepted… | OK-tested |
| 6.5 | Supplier rejects | Only its items refunded; the order goes on | Customer sees the lines struck out | KOT aSupplierRejecting… | OK-tested |
| 6.6 | Items missing while packing | Lines marked missing, refunded, stock back; wrong line 400 | Line struck out with "refunded" | same | OK-tested |
| 6.7 | Every item missing, or the only supplier rejects an order whose own part is empty | Order cancelled, ₹10 refunded too; a supplier left with nothing ends its part | — | same, KOT anOrderLeftWithNothing… | OK-tested |
| 6.7a | Supplier tries to hand over before the delivering shop accepted | 409 `NOT_ACCEPTED_YET` | "Wait until Sharma General Store accepts" | KOT aSupplierHandsOverOnlyOnce… | OK-tested |
| 6.7b | Delivering shop can't finish an accepted order (customer unreachable, wrong address, accepted by mistake) | `cancel {reason}`: whole order cancelled, refunded in full, every shop's stock back (handed items collected) | Reason shown to the customer | same | OK-tested |
| 6.7c | Delivering shop before accepting, or after rejecting | No customer details | Only the items | same | OK-tested |
| 6.8 | Out for delivery before the nearby shop handed its items | 409 `WAITING_FOR_SHOP` | "Waiting for Gupta Kirana's items" | KOT aCombinedOrder… | OK-tested |
| 6.9 | Supplier sees the order | Items and which shop to hand them to; never the customer | — | same | OK-tested |
| 6.10 | Another shop opens my part | 404 | — | same | OK-tested |
| 6.11 | Delivery code wrong | 400 `WRONG_CODE` with `triesLeft`; every 5th 429 `CODE_LOCKED` for 15 minutes (even the right code), the 15th for a day | "Wrong code, 4 tries left" | KOT aCombinedOrder…, fiveWrongCodesLock… | OK-tested |
| 6.12 | Delivered | DELIVERED; code hidden; each shop earns its own items | "Delivered" | same | OK-tested |
| 6.13 | Accept racing the timeout job, or the customer cancelling | Checkout → order → parts locked in that order; one outcome | — | — | OK-untested |
| 6.14 | An order no one finishes (a shop gone quiet) | Listed for the team 2 hours after it was placed with the shop's phone; a super admin cancels it (refunded) | Admin website | KOT theTeamSeesOrdersLeftOpen… | OK-tested |

## 7. The customer after paying

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 7.1 | Cancel before the shop accepts | CANCELLED, full refund | "Cancelled, refund on its way" | KOT theCustomerCancels… | OK-tested |
| 7.2 | Cancel after accept | 409 `CANT_CANCEL` | Cancel button hidden (`canCancel`) | same | OK-tested |
| 7.3 | Someone else's order | 404 | — | same | OK-tested |
| 7.6 | Customer deletes their account | 409 `KIRANA_ORDER_OPEN` while a shop has accepted an order; orders not accepted yet are cancelled and refunded with it | "Delete it once your order is delivered" | — | OK-untested |
| 7.4 | Rate 1–5 within 7 days | 200; twice 409 `ALREADY_RATED`; after 7 days `CANT_RATE` | Stars on the delivered order | KOT aCombinedOrder… | OK-tested |
| 7.4a | Customer typed the wrong flat number | `PATCH /orders/{id}/address` until the order goes out, at most 3 times (`canChangeAddress`); the shop told only once it accepted (the same text again tells no one); 409 `CANT_CHANGE_ADDRESS` after; another's order 404 | "Change address" on the order | KOT theCustomerCorrectsTheAddress… | OK-tested |
| 7.5 | Refund status | Each refund PENDING / PROCESSED / FAILED on the order | "₹37 refunded" | KOT aPartNotAccepted… | OK-tested |

## 8. Refunds and payouts

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 8.1 | Refund job runs twice | One Razorpay refund (our id in its notes) | — | KOT aPartNotAccepted… | OK-tested |
| 8.2 | Refund call timed out, or its lookup failed | Stays SENDING; the next run looks it up by our id before asking again (never PENDING again); each refund claimed by one run at a time | — | — | OK-untested |
| 8.3 | Razorpay refuses a refund | FAILED at once; listed for super admins | Admin website "Failed refunds" | — | OK-untested |
| 8.4 | Earnings due 2 working days after delivery | One payout per shop to its UPI ID, our id as RazorpayX idempotency key | "Paid ₹135 to sharma@ybl" | KOT aCombinedOrder… | OK-tested |
| 8.4a | Owner checks what each order earned and when it is paid | `GET /online/earnings`: one row per delivered order (the shop's part of it): order, amount, delivered, due on, the payout with its status, UPI ID and why it failed; 50 a page; helpers 403 | Earnings list | KOT theCustomerCorrectsTheAddress… (paging OK-untested) | OK-tested |
| 8.5 | Payout job runs again | Nothing sent twice | — | same | OK-tested |
| 8.6 | RazorpayX webhook: processed, failed, repeated, unsigned, out of order | `result` UPDATED (payout PROCESSED / FAILED, owner told once) / DUPLICATE / 403 `BAD_SIGNATURE`; a final status is never undone | — | KOT aCombinedOrder…, aPayoutThatFailed… | OK-tested |
| 8.7 | Failed payout | Not resent to the same UPI ID by itself; owner asked to check his UPI ID | Notice with the amount and UPI ID | KOT aPayoutThatFailed… | OK-tested |
| 8.8 | Owner changes his UPI ID after approval (or a new owner sets his after a hand-over) | Payouts held until the field team checks it; then failed payouts go to the new ID; held shops never block others' payouts | "We're checking your new UPI ID" | same | OK-tested (hand-over OK-untested) |
| 8.9 | RazorpayX keys not set yet | Earnings stay "due"; nothing sent | "Due ₹…" | — | OK-untested |
| 8.10 | Bank failure without a UPI change | Super admin retries the payout | Admin website | — | OK-untested |

## 9. Inconveniences

| # | Inconvenience | Fix | Verdict |
| --- | --- | --- | --- |
| 9.1 | Owner wouldn't know why orders can't be turned on | `missing` names each step; the 409 lists them | Fixed |
| 9.2 | Owner retypes catalogue details for every product | Barcode scan prefills from the catalogue | Fixed |
| 9.3 | Customer pays ₹10 twice for a separate order | Charged once per checkout | Fixed |
| 9.4 | A failed payout needed a team member to resend it after the owner fixed his UPI ID | Sent again automatically once the new ID is checked | Fixed |
| 9.5 | FSSAI number typed with spaces refused | Spaces dropped; printed numbers compared by digits | Fixed |
| 9.6 | An accepted order the shop couldn't finish was stuck for good | Shop cancel with a reason; the team's list of open orders | Fixed |
| 9.7 | Customer and shops couldn't reach each other | The shop's phone on the customer's order; shops' phones to each other while it is open | Fixed |
| 9.8 | A late or second payment's refund was nowhere to see | The checkout lists every refund | Fixed |
| 9.9 | The certificate the owner sent couldn't be seen or sent again with a corrected number | `fssai.fileId` | Fixed |
| 9.10 | FSSAI waiting for the AI for ever with no word | Owner told, the team's list | Fixed |
| 9.11 | Confirmation screen showed "0 items" | Item counts in the checkout's orders | Fixed |
| 9.12 | Wrong address after paying couldn't be changed; renewing a licence took the shop offline until it was read; no per-order earnings list | Address change until it goes out; the earlier licence kept during a renewal; the earnings list | Fixed |
