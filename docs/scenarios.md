# Backend on `main` — scenario list (salon and shared)

What a person (or a job) can run into, with what the API answers and which test proves it. The kirana rounds keep their
own lists (`scenarios-kirana*.md`); this one holds what `main` adds since salons and kirana are on one branch.
Verdicts: **OK-tested**, **OK-untested**, **GAP**, **N/A-v1**, **INCONVENIENCE** (works, but annoying; with the fix).
Tests: `KSO` = `KiranaSwitchOffIntegrationTest`, `KSF` = `KiranaSwitchFilterTest`, `CDT` = `ConfigurationDefaultsTest`.

## 1. Kirana switched off (`KIRANA_ENABLED` unset or `false`, the default until kirana launches)

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1.1 | The server starts without `KIRANA_ENABLED` | Kirana is off (`app.kirana.enabled=false`) | — | CDT kiranaIsSwitchedOffUnlessKiranaEnabledIsSet | OK-tested |
| 1.2 | Any call under `/api/v1/kirana/**`, `/api/v1/admin/kirana/**`, `/api/v1/payments/razorpayx/**`, signed out | 404 `KIRANA_OFF` with `message` and `details` (not 401: the filter runs before the token is read) | Kirana screens aren't on `main`'s apps | KSO everyKiranaPathAnswers404KiranaOffSignedInOrNotAdminOrNot | OK-tested |
| 1.3 | The same, signed in as a customer, a field agent (admin), a salon owner, or with a broken token | 404 `KIRANA_OFF`, the same body for everyone (says nothing more about the path) | — | same | OK-tested |
| 1.4 | A path under them that doesn't exist (`/kirana/no-such-page`) or the bare `/kirana` | 404 `KIRANA_OFF` | — | same; KSF theKiranaPrefixesAndEverythingUnderThemMatch, offAKiranaPathAnswers404KiranaOffAndGoesNoFurther | OK-tested |
| 1.5 | The path written another way (`%6Birana`, `//kirana/`, `;x`, `/./`, `/x/../kirana`, upper case) | Read as the controllers would: still 404 `KIRANA_OFF` | — | KSF pathsWrittenDifferentlyAreReadAsTheControllersWouldReadThem, theContextPathIsLeftOut | OK-tested |
| 1.6 | A near name (`/api/v1/kiranaX`) or any salon / shared path | Goes on as always (401 `NOT_SIGNED_IN` signed out, its own 404 signed in) | — | KSF onlyWholeSegmentsMatch, salonAndSharedPathsDontMatch, offAnyOtherPathGoesOnAsBefore; KSO everyKiranaPath… | OK-tested |
| 1.7 | A browser calls a kirana path from the app's site | The CORS headers are on the 404 (the filter sits after CORS), so the app can read `KIRANA_OFF` | — | (filter order in SecurityConfig) | OK-untested |
| 1.8 | `POST /shops` with `type: KIRANA`, or with no `type` (which means a kirana) | 400 `KIRANA_OFF`; no shop or membership written | Salon apps send `SALON` | KSO noShopRegistersAsAKiranaOrChangesToOneButASalonDoes | OK-tested |
| 1.9 | `POST /shops` with `type: SALON` | 201, a salon DRAFT as always | Unchanged | same | OK-tested |
| 1.10 | A salon draft `PUT /shops/me/type` to `KIRANA` | 400 `KIRANA_OFF`; it stays a salon. To `SALON` (the same): 200, nothing changes | — | same | OK-tested |
| 1.11 | A kirana row made before the switch went off submits for its visit | 409 `KIRANA_OFF`; it stays DRAFT, no field-team work | — | KSO aKiranaMadeBeforeTheSwitchWentOffIsntSubmittedForItsVisit | OK-tested |
| 1.12 | `POST /files` with purpose `BILL` or `PRODUCT_PHOTO` (a kirana's bills and packs) | 400 `KIRANA_OFF`; nothing stored | — | KSO aKiranasBillAndPackPhotosArentUploaded | OK-tested |
| 1.13 | A customer looks for kiranas, a kirana's products, items nearby | Empty lists (the paths are 404 in front anyway) | — | KSO razorpayEventsArentAKiranasAndTheCustomerListIsEmpty | OK-tested |
| 1.14 | The kirana jobs fire (`ReadJob`, `FssaiCheckJob`, `Gs1Job`, `OrderJobs` timeouts, expiry, refunds, payouts) | Nothing: no `kirana-*` lease taken, no call to the AI, GS1 DataKart, Razorpay or RazorpayX | — | KSO theKiranaJobsDoNothingAndCallNothingOutside | OK-tested |
| 1.15 | A Razorpay `payment.*` / `refund.*` event arrives | Never a kirana's (the kirana handler passes); the subscriptions' webhook goes on as before | — | KSO razorpayEventsArentAKiranas… | OK-tested |
| 1.16 | A kirana's licence waits for its check; a salon's is due later | Only the salon's is taken; the kirana's stays `PENDING` with no try counted (read once kirana is on) | — | KSO aKiranasLicenceIsntReadOrRemindedAboutButASalonsIs | OK-tested |
| 1.17 | A kirana's and a salon's licence near their last day | Only the salon is reminded; with the switch on, both (as before) | — | same | OK-tested |
| 1.18 | A kirana's owner hands the shop over while off | No kirana UPI ID or FSSAI cleared (`KiranaShopChanges` does nothing) | — | (switch check in `memberRemoved`) | OK-untested |
| 1.19 | A salon is found, set up and booked, the owner sees the day board | Exactly as before | Unchanged | KSO aSalonIsFoundAndBookedAsAlways; every Salon*IntegrationTest (run with kirana on) | OK-tested |
| 1.20 | The admin website opens shop lists and pages | As before; existing kirana rows, if any, still show | Unchanged | (no switch check on admin shop pages) | OK-untested |
| 1.21 | Kirana switched on (`KIRANA_ENABLED=true`, Railway redeploys) | Every kirana path, job and call as in the kirana sections of the README | Kirana apps | KSF onEveryPathGoesOnAsBefore; all kirana tests run with it on (`TestContainers.register`) | OK-tested |
| 1.22 | Switched off again after kirana was live | Its shops' data stays; paths 404, jobs idle; orders in flight wait (no timeouts or refunds run) until it is on again | — | — | INCONVENIENCE (only switch it off before launch; after launch, drain orders first) |
