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

## 2. Messages in the reader's language and the field team's fixed reasons (V83–V84)

Tests: `LIT` = `LanguagesIntegrationTest`, `NTT` = `NoticeTextsTest`, `RLT` = `ReadersLanguageTest`, `RTT` = `ReviewTextsTest`, `TET` = `TicketEmailsTest`, `HIT` = `HelpdeskIntegrationTest`, `SSIT` = `SalonScaleIntegrationTest`.

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 2.1 | `PATCH /auth/me {"language": "hi"}` | 200 with `language: "hi"`; `GET /auth/me` says `hi` | Partner and customer apps send it once after a choice | LIT aPersonSavesTheirLanguageAndAnythingElseIsRefused | OK-tested |
| 2.2 | Every one of `en`, `hi`, `te`, `mr`, `ta`, `kn` | 200, saved as sent | — | same | OK-tested |
| 2.3 | Anything else (`fr`, `HI`, `hindi`, `""`) | 400 `VALIDATION_FAILED` `details.language`; nothing changes | The apps send only their six codes | same | OK-tested |
| 2.4 | A wrong language sent with a name | 400; the name isn't saved either (all or nothing) | — | same | OK-tested |
| 2.5 | `"language": null` | 200, `language: null`: English again | — | same | OK-tested |
| 2.6 | The name alone (as before) | 200; the language stays | Unchanged | same | OK-tested |
| 2.7 | An empty body (neither field) | 400 `details.name`, as before | — | same | OK-tested |
| 2.8 | A value the database refuses (written around the API) | `ck_users_language` (V83, validated in V84) refuses it | — | same | OK-tested |
| 2.9 | A new account with no email or name sends only its language (right after the first sign-in) | 200 (no `PROFILE_INCOMPLETE`) | Saved at once | LIT aNewAccountWithoutEmailOrNameCanSaveItsLanguage | OK-tested |
| 2.10 | Deleting the account | `language` cleared with the rest of the person's details | — | (`User.anonymise`) | OK-untested |
| 2.11 | A shop sent back; the owner reads Hindi | `SHOP_NOT_APPROVED` title and body in Hindi, the salon's name and the team's words as they are | Inbox and push in Hindi | LIT aShopSentBackIsToldInTheOwnersLanguageAndInEnglishWhenNoneIsChosen | OK-tested |
| 2.12 | The same for an owner who chose no language | The English, word for word as before | As before | same | OK-tested |
| 2.13 | A batch (`queueAll`) to people in Hindi, Telugu and none | Each in theirs; **one** lookup of the readers' languages for the whole batch | — | LIT aBatchIsWrittenInEachReadersLanguageWithOneLookup | OK-tested |
| 2.14 | A batch whose notices carry no text | No lookup at all; as written | — | same | OK-tested |
| 2.15 | A language lacking a message's body (or a piece, or a blank's value) | Title **and** body in English, never one of each | — | RLT aBodyThatCantBeWrittenInTheLanguageKeepsItsTitleInEnglishToo, withEveryValueBothAreInTheLanguage | OK-tested |
| 2.16 | A translation missing a text, or with other blanks than English | The build's tests fail | — | NTT everyLanguageHasEveryTextWithTheSameBlanks | OK-tested |
| 2.17 | The code's English drifts from `en.properties` | Tests (strict) fail; production sends the code's English unchecked | — | RLT theEnglishIsCheckedAgainstTheTextsOnlyWhenStrict; NTT englishSaysWhatTheCodeAlwaysSaid | OK-tested |
| 2.18 | Dates and times inside a message for a Telugu reader | Day and month names in Telugu, digits 0-9, the time "4:30 PM" as in English; names, codes, amounts as data | — | NTT otherLanguagesGetTheirDayNamesTheSameTimeAndTheirWordsForPieces | OK-tested |
| 2.19 | Many messages with dates at once | One day formatter per pattern and language, made once | — | NTT aDayPatternIsMadeOnceForEachLanguage | OK-tested |
| 2.20 | The SMS of a message to a Hindi reader | Unchanged (the DLT template) | — | SSIT aCustomerWhoChooses… (SMS variables unchanged) | OK-tested |
| 2.21 | A customer changes language with a booking reminder waiting | His waiting reminders rewritten in the new language, in the change's transaction; someone else's untouched; back to none: English again | Push and inbox in the new language | SSIT aCustomerWhoChoosesAnotherLanguageGetsHisWaitingRemindersInItAndOnlyHis | OK-tested |
| 2.22 | A reminder already sent when he changes language | Stays as it went out | — | same | OK-tested |
| 2.23 | A salon renames itself with reminders waiting for readers of several languages | One statement; each reminder in its reader's language | — | (`rewriteReminders` joins `users.language`) | OK-untested (several languages at once) |
| 2.24 | His other booking messages queued just before a change | Go out at once in the language of the moment they were queued | — | — | OK-untested (by design: none waits) |
| 2.25 | Kirana-only messages, and a kirana's reinstatement while it adds products | English | — | — | N/A-v1 (kirana texts later) |
| 2.26 | Send back with `reasonCodes` and words | 200; codes stored in order, words as the note; the owner's message lists the reasons in his language, then the words | Partner: "What to fix:" list with Fix links, "Note from the team" | LIT reasonCodesAreSavedAndShownToTheOwnerAndTheTeam | OK-tested |
| 2.27 | Codes only, no words | 200; `statusReason` null in every answer (stored `''`: still "sent back" for the lists) | Only the list | same | OK-tested |
| 2.28 | The same code twice | Kept once, the order of first appearance | — | same | OK-tested |
| 2.29 | An unknown code, or more than 14 | 400 `details.reasonCodes` | Admin: under the list | same | OK-tested |
| 2.30 | `OTHER` without words | 400 naming the words' field | Admin: "Write what is wrong" | same; RTT aProblemWithTheWordsNamesTheFieldTheyCameIn | OK-tested |
| 2.31 | `OTHER` with words | 200; the words are all the owner is told (`OTHER` never listed) | The note | same | OK-tested |
| 2.32 | `reasonCodes: []` and no words | 400 `details.reason` (the old rule) | — | same | OK-tested |
| 2.33 | An older admin app (`reason` only) | 200; the owner's message exactly what it said before | — | LIT theOldFreeReasonIsStillAcceptedAndSaysExactlyWhatItSaidBefore | OK-tested |
| 2.34 | A row from before V84 | `statusReasonCodes: []`, the old words as `statusReason` | The words in the sentence, as before | same | OK-tested |
| 2.35 | Ask to fix on a live re-check with codes; a declined address with codes | Codes on the shop, the request and the timeline; `requesterLanguage` on the admin's address request | Partner: the list on the re-check card and the address page | LIT aLiveShopAskedForAFixAndADeclinedAddressCarryTheirCodes | OK-tested |
| 2.36 | Decline with the old body `{version, reason}` | 200 as before | — | same | OK-tested |
| 2.37 | Decline with every reason and 199 characters of words | 200; the timeline note holds only "Address change declined: " and the words (fits `VARCHAR(500)`); the reasons in `reason_codes` | Admin timeline: pills then the note | LIT aDeclineWithEveryReasonAndLongWordsFitsTheTimelineAndAProblemNamesTheFieldSent | OK-tested |
| 2.38 | Send back with every reason, 200 characters of words and a 500-character internal note | 200; the timeline keeps the words and the note, the reasons beside them | — | LIT aSendBackWithEveryReasonTheLongestWordsAndNoteFitsTheTimeline | OK-tested |
| 2.39 | Bad words on a decline: sent as `note` / as `reason` | 400 naming `note` / `reason`; nothing decided, the request still waits | Admin: under the note | LIT aDeclineWithEveryReason…AProblemNamesTheFieldSent | OK-tested |
| 2.40 | A reason whose English text is missing (a gap) | The decision still goes; the code's name in the message, a WARN logged | — | RTT reasonsInEnglishAndAReasonWithoutWordsFallsBackToItsName | OK-tested |
| 2.41 | Every reason, licence answer and document type in every language | Each has its text | — | RTT everyReasonLicenceAnswerAndDocumentHasItsTextInEveryLanguage | OK-tested |
| 2.42 | The team sees which language the person reads | `ownerLanguage` on the shop (lists and page), `requesterLanguage` on the address request and the help ticket; null for none | Admin: "Reads: Tamil" | LIT adminPagesShowWhichLanguageThePersonReads | OK-tested |
| 2.43 | A partner app from before 0.29.0 gets a send back with codes only | `statusReason` null | The old app shows no words: release the partner app before the admin app 0.19.0 | — | OK-untested (a release step) |
| 2.44 | A help ticket from someone who reads Hindi | The acknowledgement and the reply's wrapper in Hindi; `[CLZ-1001]` exactly so in the subject (a reply finds the ticket); the support person's reply and the quotes as written | — | HIT ourEmailsAreInTheLanguageOfTheirAccountWithTheTicketNumberAndTheReplyAsTyped; TET everyOtherLanguageKeepsTheNumberTheReplyAndTheQuotesAndHasNoEnglishWords | OK-tested |
| 2.45 | Someone with no account (only emailed), or no language | English, as before | — | HIT same; TET englishIsWhatItAlwaysWas, noLanguageIsEnglish | OK-tested |
| 2.46 | A language missing one of the email's texts | The whole email in English, never half | — | TET noLanguageIsEnglish (every language has every text) | OK-tested |
| 2.47 | Many at once: a message to lakhs of people | One `SELECT … WHERE id = ANY (?)` for the batch's languages; each reminder rewrite one statement per salon; the admin lists read owners' languages in the same query as their phones | — | LIT aBatchIsWritten…WithOneLookup; MessagesAtScaleIntegrationTest (runs through the languages lookup) | OK-tested |
| 2.48 | Two phones change the same person's language at the same moment | The person's row is locked; the last one wins, and his waiting reminders follow it | — | — | OK-untested |

## 3. Salon search (`GET /salons?q=`, V85; customer app 0.27.0)

Tests: `SSI` = `SalonSearchIntegrationTest` (real Postgres), `SST` = `SalonSearchTest`, `SDN` = `ShopDirectoryNearbyTest`. The customer app's rows are in its own `docs/scenarios.md`.

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 3.1 | No q / q blank or only spaces | Exactly today's list (no extra SQL) | All salons near | SSI `qIsTwoToSixty…` (page("") equals page(null)), SDN `withoutASearch…` | OK-tested |
| 3.2 | Salon name in part, any case ("RAJU", "aju h") | That salon only | Its card | SSI `aSalonIsFoundByItsNameInPartAndInAnyCase` | OK-tested |
| 3.3 | Service name ("facial" → "Gold Facial") | Salons whose active service a working barber does matches | Matching cards, nearest first | SSI `aSalonIsFoundByAServiceItsBarbersDo…` | OK-tested |
| 3.4 | Service picture ("facial" → "Face clean-up" with FACIAL icon; "color" → HAIR_COLOUR; "mehndi" → MEHENDI) | Matched by the icon's name or an alias word (word start only) | Card | SSI same test, SST `aPictureIsNamed…` | OK-tested |
| 3.5 | Small spelling slip from 4 letters ("facal", "mehndi", "threding", "rajoo") | Word by word: each word of 4+ letters is in the name or near one of its words (pg_trgm `similarity` ≥ 0.35 of the two words, and 1 letter more or fewer, 2 from 7 letters); each shorter word must be in the name | Card | SSI `smallSpellingSlipsAreForgiven…` ("mehndi", "threding", "rajoo"), SSI `everyWordMustMatch…` ("facal", "rajoo saloon"); SST `theNameIsMatchedWordByWord…` | OK-tested |
| 3.5a | One common word shared is not a match: "haircut" vs "Raju Hair Salon" / "Hair Spa", "lakme salon" vs "Style Salon", "raju hair salon" vs "Ravi Hair Salon", "head massage" vs "Body Massage", "shave" vs "Shampoo", "face" vs "Gold Facial" | Not found (each word must match on its own) | Not shown | SSI `everyWordMustMatchOnItsOwn…`, SSI `aSalonIsFoundByAService…` ("HAIRCUT", "face") | OK-tested |
| 3.5b | Words in another order ("salon raju") | Found (every word matches) | Card | SSI `everyWordMustMatchOnItsOwn…` | OK-tested |
| 3.5f | A name with a word joined to punctuation ("Raju's Salon", "Eyebrow Threading/Shaping", "Facial/Cleanup") | The name is split at spaces and punctuation, so "rajoo", "threding", "facal" still find them; Indian vowel signs stay in their word | Card | SSI `aNameIsSplitIntoWordsAtPunctuation…` | OK-tested |
| 3.5c | q typed as full-width letters ("Ｒａｊｕ"), the one-character "फ़" (U+095E), a no-break space between words | Tidied as saved names are (`TextCleaner.tidy`: NFKC, any space → one space) before the length check and lower case: matches | Card | SST `theTextIsTidied…`, SSI `whatWasTypedIsTidied…` | OK-tested |
| 3.5d | Hindi: name in part ("राजू", "हेयर सैलून"), a vowel-sign slip ("राजु"), a service typed with "फ़" | Found; the slip needs a UTF-8 character type in the database (pg_trgm sees no Hindi words under `C`) | Card | SSI `whatWasTypedIsTidied…` (postgis image: en_US.utf8); checked on local PG 16 under `C.utf8` (found) and `C` (slip not found) | OK-tested |
| 3.5e | The card of a salon found by a service: which services it names | The matched services first in `topServices` (same field), then the others in the salon's order; found by its name: the salon's order | The card says why it was found | SSI `theServicesASearchMatchedComeFirst…` | OK-tested |
| 3.6 | Same cards as without search | Identical SalonSummary per salon | Same card | SSI compares card maps | OK-tested |
| 3.7 | Switched-off service, service no working barber does, barber switched off, paused salon, salon > 2 km, a kirana | Never matched | — | SSI `aSwitchedOffService…NeverFound` | OK-tested |
| 3.7a | The only barber who does the service has no weekly hours | Not found by that service (nobody can be booked); still found by its name | — | SSI `aServiceOnlyABarberWithoutWeeklyHours…`, SDN (staff_hours in the statement) | OK-tested |
| 3.7b | Subscription lapsed (SUBSCRIPTIONS_ENFORCED on) | Not found by name or service; a paying salon is | — | SSI `aSalonWhoseSubscriptionLapsedIsNeverFound` | OK-tested |
| 3.8 | Nothing matches | 200 `[]` | Empty state (see app) | IT, app e2e | OK-tested |
| 3.9 | Paging with q (35 matches + 5 nearer non-matches, two of them at the same distance across the page break) | 30 then 5, same `afterDistance/afterId`, sorted, none lost or twice (the id breaks the tie); non-matches never cut a page short | "Show more salons" | SSI `searchResultsCome30APage…` | OK-tested |
| 3.10 | q of 1 char, "  f  ", "\", 61 chars | 400 `VALIDATION_FAILED` `details: {q: …}` | App never sends these | IT, SST `oneCharacterOrMoreThanSixty…` | OK-tested |
| 3.11 | 60 chars, or 60 Hindi letters (code points, not bytes) | 200 | — | IT, unit | OK-tested |
| 3.12 | `%`, `_`, `\` in q ("0%", "a_b", "a\b", "%%", "__") | Taken literally (LIKE escaped, no typo matching for non-letters) | — | SSI `percentUnderscoreAndBackslash…`, unit; verified on local PG 16 | OK-tested |
| 3.13 | Many customers searching at once (thousands of salons) | One statement per page: PostGIS radius index first (≤ a few hundred salons within 2 km), each checked via index-only scan on V85 `ix_salon_services_search`; no per-salon reads from Java; cards still 1 query/page | — | A manual EXPLAIN on 20k salons × 15 services locally (~10–25 ms cold, services read index-only), not a test that runs; SDN checks it stays one statement | OK-untested |
| 3.14 | One person searching very often (a stuck key, a script) | With q: 30 searches a minute per person (`app.salon.searches-per-minute`), then 429 `TOO_MANY_REQUESTS` with `Retry-After` (seconds), counted apart from the salon pages' 60; without q never counted; Redis down: let through. No server cache; the app caches per (lat, lng, q) | The app's "wait a few seconds" (see app) | SSI `thirtySearchesAMinuteThenA429…` | OK-tested |

## 4. Support's reply in the apps' "My requests" (`lastReplyAt`)

Tests: `HIT` = `HelpdeskIntegrationTest`. The apps' rows (the "answered" dot) are in their own `docs/scenarios.md`.

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 4.1 | Support replies and resolves in one step (`POST /admin/tickets/{id}/reply`, `resolve: true`) | The ticket is RESOLVED (never WAITING); `GET /helpdesk/tickets` items and `GET /helpdesk/tickets/{id}`'s `ticket` carry `lastReplyAt` = the time of support's newest message | The apps light "answered" from `lastReplyAt` newer than what was read | HIT `aReplyThatAlsoResolvesSetsLastReplyAt…` | OK-tested |
| 4.2 | No reply yet / the person writes again | `lastReplyAt` null until support writes; the person's own message moves `lastMessageAt`, never `lastReplyAt`; each ticket its own | No false dot | HIT same test | OK-tested |
| 4.3 | Many tickets, many people | One statement for the list (each ticket's newest reply read backwards through `ix_ticket_messages_ticket (ticket_id, seq)`, at most 30 tickets); the single read takes it from the messages it already reads; no new index or migration | — | HIT; plan reasoning | OK-untested (load) |

## 5. The admin website's email step (V86)

Tests: `ACI` = `AdminCodeIntegrationTest`, `RGS` = `RefreshGraceSettingTest`. The website's rows are in the admin
app's `docs/scenarios.md`.

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 5.1 | An admin signs in with the phone code | Tokens as usual; `user.adminCodeNeeded: true`; the token has no admin access: `/api/v1/admin/**` 403 `FORBIDDEN`; their own account (`/auth/me`) works | Admin website: "Check your email" step | ACI `aPhoneCodeAloneGivesAnAdminNoAdminPages…` | OK-tested |
| 5.2 | They ask for the code, type a wrong one, then the right one | 202; 400 `WRONG_CODE` (nothing recorded); 200 with a new access token that has admin access, `adminCodeNeeded: false` | Into the website | ACI same test | OK-tested |
| 5.3 | The same code again | 400 `CODE_EXPIRED` (works once) | — | ACI same test | OK-tested |
| 5.4 | Refreshes on that device | Each new token keeps admin access, `adminCodeNeeded: false` | No step again | ACI same test | OK-tested |
| 5.5 | A SIM thief signs in on their own phone and tries the code the owner's laptop asked for | 400 `CODE_EXPIRED` (the code is for the laptop's sign-in only); the thief's refreshes stay without admin access | — | ACI `theCodeIsForTheDeviceThatAsked…` | OK-tested |
| 5.6 | 12 hours after the step | The next refresh: no admin access, `adminCodeNeeded: true` | The website asks for a new code | ACI `after12HoursTheStepIsAskedAgain…` | OK-tested |
| 5.7 | Removed from the team after passing the step | Next refresh: `admin: false`, no admin access; `/admin-code/send` 403 `NOT_AN_ADMIN` | "This is for the Clozest team" | ACI same test | OK-tested |
| 5.8 | A customer, salon owner or barber | `adminCodeNeeded: false` always; `/admin-code/*` 403 `NOT_AN_ADMIN` | Apps unchanged | ACI `customersNeverSeeTheStep…` | OK-tested |
| 5.9 | An admin with no verified email | `/admin-code/send` 409 `EMAIL_REQUIRED`, no email sent | The email step first | ACI same test | OK-tested |
| 5.10 | Not signed in / empty code | 401 / 400 `VALIDATION_FAILED` | — | ACI same test | OK-tested |
| 5.11 | `ADMIN_CODE_HOURS` set outside 1 to 24 | The server refuses to start | — | RGS `theAdminEmailStepLasts1To24Hours` | OK-tested |
| 5.12 | Many admins and devices | One row per device sign-in that passed the step; an admin's old rows deleted when they pass it again; one primary-key read per admin sign-in or refresh, none for anyone else | — | plan reasoning | OK-untested (load) |
| 5.14 | A SIM thief changes the admin's email to their own inbox with a phone code, then asks for the admin code | `/auth/email/send-code` and `/verify` 403 `ADMIN_EMAIL` (an admin's email is set on the Team page); the code only ever goes to the team's address | — | ACI `aSimThiefCantMoveTheEmail…` | OK-tested |
| 5.15 | Taken off the team and added back within 12 hours | The old pass doesn't count (`verified_at` before `admin_since`): the step again | The website asks for a code | ACI same test | OK-tested |
| 5.16 | A device signed out, its access token still valid for minutes | `/admin-code/verify` 401 `SIGN_IN_AGAIN` | — | ACI same test | OK-tested |
| 5.13 | Email down | `/admin-code/send` 503 `EMAIL_FAILED` (as every email code) | "Try again" | existing email tests | OK-untested |

## 6. Database users (V87, `MigrateOnly`, GitHub's Migrate job)

Tests: `ADR` = `AppDatabaseRoleIntegrationTest`, `PSC` = `ProductionSafetyCheckTest`.

| # | Scenario | Answer | Test | Verdict |
| --- | --- | --- | --- | --- |
| 6.1 | GitHub's job migrates a database as the owner | Every migration runs, as the app runs them (mixed, no lock transaction, no statement limit) | ADR `migrateOnlyRanEveryMigration…` | OK-tested |
| 6.2 | The job is given Neon's pooled address | Refused before anything runs | ADR same test | OK-tested |
| 6.3 | The app runs as `clozest_app` with `MIGRATE_ON_START=false` | Starts (Hibernate's table check), sign-in and the account work; 5 s statement limit is the user's default | ADR `theAppStartsAsClozestApp…` | OK-tested |
| 6.4 | Someone with `clozest_app`'s password tries to create, drop, change, empty or re-trigger a table, or edit Flyway's history | `permission denied` / `must be owner` | ADR `clozestAppChangesRowsButNeverTables` | OK-tested |
| 6.5 | A later migration adds a table | `clozest_app` can use it at once (default privileges) | ADR same test | OK-tested |
| 6.6 | The secrets aren't set yet | The job passes with a notice; the server migrates itself as before | workflow | OK-untested |
| 6.7 | Production still migrating itself | Starts, with a WARN pointing to "Database users" | PSC `aServerThatStillMigratesItselfIsNotedNotRefused` | OK-tested |
| 6.8 | Production with `MIGRATE_ON_START=false` and no `DB_URL_DIRECT` | Starts (the direct URL is only for migrations) | PSC `productionMigratesOnlyThroughNeonsDirectUrl` | OK-tested |

## 7. Scale round 1: sign-out check memory, weekly parts (V88), read copy

Tests: `SR` = `SessionRevocationsTest`, `ADR` = `AppDatabaseRoleIntegrationTest`, `MU` =
`MigrationUpgradeIntegrationTest`, `WP` = `WeeklyPartsTest`, `AI` = `AuthIntegrationTest`, `MS` =
`MessagesAtScaleIntegrationTest`, `SB` = `SalonBookingIntegrationTest`, `RC` = `ReadCopyTest`, `RCI` =
`ReadCopyIntegrationTest`, `SDN` = `ShopDirectoryNearbyTest`.

| # | Scenario | Answer | Test | Verdict |
| --- | --- | --- | --- | --- |
| 7.1 | The same person makes many requests within 5 s | Redis is asked once; the number is remembered | SR `theCheckIsRememberedForAFewSecondsAndOnlyEverRaised` | OK-tested |
| 7.2 | They sign out everywhere on copy A; old token used on A | Refused at once (A raised its own memory) | SR `endingAllSessionsHereIsKnownHereAtOnce` | OK-tested |
| 7.3 | Same, old token used on copy B that remembered the old number | Refused within 5 s (B reads Redis again then) | SR `anotherCopyRefusesTheOldTokenOnceItsMemoryRunsOut` | OK-tested |
| 7.4 | A late, lower number from Redis (a race) | Ignored: the memory only rises | SR remembered-test | OK-tested |
| 7.5 | `SIGN_OUT_CHECK_CACHE_SECONDS=0` or above 60 | 0 asks Redis every time; above 60 or below 0 refuses to start | SR `zeroSecondsAsksRedisEveryTime`, `theMemoryMayLastAtMostAMinute` | OK-tested |
| 7.6 | Redis down, memory on | As before: requests pass (tokens last 15 min), nothing remembered meanwhile | SR `withTheMemoryOnRedisDown…` | OK-tested |
| 7.6a | A Redis key that expired (31 days without a change) | No copy still remembers it by then; the test forgets it as a restart does | AI `aSessionNumberThatExpired…` | OK-tested |
| 7.7 | V88 on a database with rows | Today's rows in this week's part, older ones in the default part; every column kept; checks and the user link still enforced; this week + 4 made | MU `signInsAndMessagesMoveIntoWeeklyParts…` | OK-tested |
| 7.8 | Hibernate's table check against a partitioned `refresh_tokens` | Passes (`PARTITIONED TABLE` counted) | every integration test starting | OK-tested |
| 7.9 | Nightly: the coming weeks | Made once; asking again makes none | ADR `clozestAppKeepsTheWeeklyParts…` | OK-tested |
| 7.10 | An old week whose sign-ins all ended over 30 days ago | Dropped, one week per call (its own transaction) | ADR same; WP `makesTheComingWeeks…` (called again after each drop) | OK-tested |
| 7.10a | Months of weeks to drop after a long stop | At most 20 a night | WP `aBacklogGoesAtMost20WeeksANight` | OK-tested |
| 7.10b | The job missed a month: a week's rows already in the default part | That week skipped (WARN), the next weeks made; its rows deleted one by one | ADR same | OK-tested |
| 7.11 | An old week with one sign-in ended 10 days ago | Kept (IN_USE) | ADR same | OK-tested |
| 7.12 | An old week of messages, one still waiting | Kept; dropped once it finished 90+ days ago | ADR same | OK-tested |
| 7.13 | This week or a coming one | Never looked at by the drop | ADR same | OK-tested |
| 7.14 | `clozest_app` drops a part itself, or calls the functions for another table, 0 keep days, 500 weeks | `must be owner` / refused | ADR same | OK-tested |
| 7.15 | The table is busy when dropping or making a week | Waits at most 1 s, left for next night (WARN) | SQL `lock_not_available` handler | OK-untested (lock wait) |
| 7.16 | A function call fails outright | Logged, `maintain` returns; the jobs' row cleanup comes after it in the same method | WP `neitherStepFailingStops…` (the jobs' order: by reading) | OK-tested (WP) |
| 7.17 | Ended sign-ins in the default part (before V88, or a week made late) | Deleted one by one after 30 days, as before | AI `endedSignInsAreDeletedAfter30Days…`, `aCopiedTokenRefreshedAlongAChain…` | OK-tested |
| 7.18 | A copied token chain after the nightly cleanup | Still caught (swapped tokens stay 30 days) | AI chain test | OK-tested |
| 7.19 | Finished messages in the default part | SMS after 7 days, others after 90, waiting ones never | MS `cleanupDeletesInSlices…`, SB `finishedMessagesAreDeletedAfter90Days…` | OK-tested |
| 7.20 | A week stuck in use 60 days after it could have gone | WARN naming the part | WP (June week runs the WARN path; the log line isn't asserted) | OK-tested (path) |
| 7.20a | A booking reminder a month ahead keeps its week | The week stays ~4 months; the lists and the bell still show only 90 days | NotificationStore list reads (`send_at > now − 90 days`) | OK-untested |
| 7.21 | No `READ_DB_URL` | The salon list reads the main database, as before | RC, SDN, SB | OK-tested |
| 7.22 | `READ_DB_URL` set, copy down | Backend starts; the list is read from the main database (WARN once a minute) | RC `aCopyThatIsDown…` | OK-tested |
| 7.22a | The copy cancels a query (conflict with its replay) | Read again on the main database, never a 409 | ReadCopy catch (same path as 7.22) | OK-tested (path) |
| 7.23 | A write through the read copy by mistake | Refused (read-only, also outside a transaction) | RCI | OK-tested |
| 7.24 | The read copy a moment behind | The list may show a just-paused or renamed salon the old way for under a second; bookings read the main database | design | OK-untested |

## 8. The Android apps' notifications (FCM, V89, V91)

Tests: `SB` = `SalonBookingIntegrationTest`, `FS` = `FcmSenderTest`, `MU` = `MigrationUpgradeIntegrationTest`.

| # | Scenario | Answer | Test | Verdict |
| --- | --- | --- | --- | --- |
| 8.1 | The app asks whether notifications can be turned on | `GET /push/public-key` → `appPush: true` (Firebase set up) or `false` | SB `theAndroidAppSavesItsToken…`, `withoutFirebaseOnTheServer…` | OK-tested |
| 8.2 | The app saves its token | 204; one `APP` row; saving again (every sign-in) keeps one row | SB `theAndroidAppSavesItsToken…` | OK-tested |
| 8.3 | Something that isn't a token (short, a browser's https address) | 400 `VALIDATION_FAILED` on `token` | SB same (asserts the code and the field) | OK-tested |
| 8.4 | Firebase not set up on the server | 409 `APP_PUSH_OFF`, nothing saved; the app shows "not available yet" | SB `withoutFirebaseOnTheServer…` | OK-tested |
| 8.5 | Signed out (no token) | 401 | SB `theAppsTokenMovesToWhoever…` | OK-tested |
| 8.6 | Someone else signs in and turns notifications on, on the same phone | The token moves to them; the first person gets nothing on that phone | SB same | OK-tested |
| 8.7 | Sign-out removes the token; removing someone else's token | Own: deleted (204); someone else's: nothing deleted (204) | SB same | OK-tested |
| 8.8 | A person with the website on one phone and the app on another | Each message goes to both: Web Push to the browser, FCM to the app (title, text, type, screen) | SB `theAndroidAppSavesItsToken…` | OK-tested |
| 8.9 | The app was uninstalled: FCM 404 `UNREGISTERED`, 400 `INVALID_ARGUMENT` about the registration token, or 403 `SENDER_ID_MISMATCH` | The app's row deleted; the browser kept | SB same; FS `anUninstalledAppIsGone…` | OK-tested |
| 8.9a | FCM refuses our message itself (400 `INVALID_ARGUMENT` about a field) | Never deletes the device: retried, WARN logged | FS same | OK-tested |
| 8.10 | FCM busy or down (429, 5xx, no answer), the app the person's only device | FAILED: retried after 1, 5, 15 minutes like Web Push | FS same | OK-tested |
| 8.10a | FCM down while the person's browser got the message | Retried for the app only (`sent_devices` keeps the browser): the browser never gets it twice; SENT once the app has it, or after the last try | SB `firebaseDownWhileTheBrowserGotIt…` | OK-tested |
| 8.11 | Many sends at once | One Google sign-in for ~55 minutes shared by all; renewed by one send 5 minutes before expiry; per-host limit `fcm-app` caps sends at once | FS `signsInOnceForManySends…`; the per-host limit as MS's per-host test | OK-tested (sign-in), OK-untested (`fcm-app` limit, same code as browsers') |
| 8.12 | The access token is refused (401) | That send fails (retried); the token is dropped and the next send signs in again | FS `aRefusedAccessTokenIsDropped…` | OK-tested |
| 8.13 | The service-account key is wrong or deleted | Every app send FAILED (retried, then FAILED; SENT where a browser got it), WARN "check FIREBASE_SERVICE_ACCOUNT"; devices kept | FS `aRefusedSignInFails…` | OK-tested |
| 8.14 | `FIREBASE_SERVICE_ACCOUNT` isn't a key file | The server refuses to start (as a wrong VAPID pair) | FS `theKeyFileIsRead…` | OK-tested |
| 8.15 | Firebase removed after apps registered | Their messages FAILED after the retries (SENT where a browser got them); rows kept for when it is set again | `AppPushSender.OFF` (by reading) | OK-untested |
| 8.16 | 10 browsers saved, then the app | Still 10: the oldest browser goes, the app stays | SB `anAppAndBrowsersShareTheTenDevicesLimit` | OK-tested |
| 8.17 | Sign out everywhere, change of phone number, delete the account | Every device goes, apps included (same table). A session that just expires leaves the row: the app drops its token (Firebase) when next opened | existing SB and AI tests of `deleteAll` | OK-tested |
| 8.18 | V89 and V91 on a database with saved browsers | They become `WEB` with their keys; the check is validated (V91, its own transaction); a browser without keys, an app with keys, an unknown kind are refused | MU `savedBrowsersBecomeWebDevices…` | OK-tested |
| 8.19 | The token in the logs | Never: only the device id and FCM's status and code | `FcmSender` (by reading) | OK-tested (review) |

## 9. Paying the subscription in the Play Store app (user choice billing, V90)

Tests: `IA` = `InAppSubscriptionIntegrationTest`, `MU` = `MigrationUpgradeIntegrationTest`, `PS` = `PincodeStatesTest`,
`GP` = `GooglePlayTest` (the API client against a local fake).

The app offers paying only once the salon's free or paid time is over (`inAppPayFrom`, 15 minutes before it): Google
Play has no free trial (a trial at Google would repeat the salon's own, once per Google account), and Razorpay from
the app charges at once too. The website is unchanged: an owner can still set up AutoPay there before the end.

| # | Scenario | Answer | Test | Verdict |
| --- | --- | --- | --- | --- |
| 9.1 | The app asks whether and from when it may take payment | `inAppProductId` (null while Google Play isn't set up), `inAppPayFrom` (null then too), `googlePlay` false | IA `theAppSaysPayingInTheAppIsPossible…`; null: SubscriptionStateTest (no Google Play) | OK-tested |
| 9.1a | The app's checkout during the free (or a paid) time | 409 `PAY_IN_APP_LATER` ("You can pay here from …"); the website's checkout still works | IA `duringTheTrialTheAppCantOpenAPayment…` | OK-tested |
| 9.2 | Razorpay picked once the time is over: checkout with Google's token | 200; the token kept with the subscription offered, under the salon's lock | IA `razorpayFromTheApp…` | OK-tested |
| 9.3 | AutoPay approved, then charged; the charge's webhook sent twice | Approving is no payment (nothing queued); the first charge is the first transaction (₹500, with the token), queued once; the salon's state from its PIN code | IA same | OK-tested |
| 9.4 | Reporting | The first with the token, RECURRING; each later charge naming it; amounts in micros, INR | IA same | OK-tested |
| 9.5 | A later charge while the first isn't with Google yet | Waits a minute and asks again (Google needs the first first) | PlayReports (by reading) | OK-untested |
| 9.6 | Google down (5xx, 429, no answer) | Tried again after 1, 2, 4 … minutes (at most an hour apart); not sent before its time | IA `googleNotAnswering…` | OK-tested |
| 9.7 | Google already has it (409) | DONE | IA same | OK-tested |
| 9.8 | Google refuses the first (400) | REFUSED, ERROR logged (report by hand); its later charges REFUSED at once, Google not asked, no retry every minute | IA same | OK-tested |
| 9.9 | A report still waiting after 20 hours | ERROR logged each round | PlayReports (by reading) | OK-untested |
| 9.9a | Two servers send reports at the same moment | Each round claims its rows (next try moved in the same statement): one report sent once | IA `twoServersRunningTheReports…` | OK-tested |
| 9.9b | A round that runs long (Google slow) | Stops after 3 minutes; its claimed rows come back after 10 minutes | PlayReports (by reading) | OK-untested |
| 9.10 | The owner opens the app's checkout, then pays on the website within the hour | The website gets a new subscription of its own, the app's is cancelled: nothing of the website's is reported, and the app never reuses the website's | IA `theWebsiteNeverReusesTheAppsCheckout…` | OK-tested |
| 9.11 | Something that isn't a token (spaces, too short) | 400 `VALIDATION_FAILED` (any printable token of 10 to 1000 characters is taken: Google documents no alphabet) | IA `notGooglesTokenIsRefused` | OK-tested |
| 9.12 | Google Play not set up, app sends a token | 503 `IN_APP_PAYMENTS_OFF` | SubscriptionService (by reading) | OK-untested |
| 9.13 | Google Play picked | Checked, paid until its end, AUTOPAY with `googlePlay`, then acknowledged | IA `aGooglePlayPurchaseIsChecked…` | OK-tested |
| 9.13a | Google doesn't take the acknowledgement now (any failure other than "already acknowledged") | No error to the owner (paid shows); the recheck acknowledges it within minutes (Google refunds after 3 days otherwise) | IA `googleNotTakingTheAcknowledgement…` | OK-tested |
| 9.14 | Then the website's checkout | 409 `ALREADY_SUBSCRIBED` (no double charge) | IA same | OK-tested |
| 9.15 | The same purchase sent again | 200, nothing changes | IA same | OK-tested |
| 9.16 | Another salon's purchase, another product, an unknown token, a licence tester's purchase in production | 400 `PLAY_PURCHASE_INVALID`, nothing saved | IA `aPurchaseForAnotherSalon…` | OK-tested |
| 9.17 | A purchase the salon dropped (replaced, or the salon closed) sent again | 409 `PLAY_PURCHASE_TAKEN` | IA `aSecondPurchaseReplacesTheFirst…` | OK-tested |
| 9.17a | A second purchase (another Google account on another phone) | The newest paid one is the salon's; the older one stops renewing (cancelled at Google, tried until done) and is never attached again | IA same | OK-tested |
| 9.17b | An older purchase, cancelled in the Play Store, found later (sent by another phone's app, or by Google) | Never replaces the salon's renewing one, never acknowledged by us (Google refunds it); 200 with the salon's view | IA `anOldCancelledPurchaseFoundLater…` | OK-tested |
| 9.18 | Google Play bought while a Razorpay subscription is live (approved, being retried, or a checkout that could still be approved) | That Razorpay subscription is cancelled | IA `buyingWithGooglePlayStopsAnyLiveRazorpay…` | OK-tested |
| 9.18a | A Razorpay approval arriving while Google Play renews | That Razorpay subscription is cancelled; no "AutoPay is on" (nor "failed", "stopped") message about it | IA same | OK-tested |
| 9.18b | A cancelled Google Play subscription restored in the Play Store after Razorpay AutoPay was set up | Google Play renews again: Razorpay's subscription is cancelled | PlaySubscriptions.apply (by reading; the same call as 9.18) | OK-untested |
| 9.19 | Google's notification: wrong secret | 403 `BAD_SECRET` | IA `googlesNotifications…` | OK-tested |
| 9.20 | Renewal notification | Paid end moves on | IA same | OK-tested |
| 9.21 | Cancelled in the Play Store | Paid time stays; no longer AutoPay (state PAID, then reminders) | IA same | OK-tested |
| 9.21a | A free trial at Google (should one be set up by mistake) or Google's grace period after a failed renewal | Never counted as paid, no "₹500 received"; in the grace period the page says Google Play couldn't take the payment and links to the Play Store | IA `aFreeTrialOrGooglesGracePeriod…` | OK-tested |
| 9.21b | Refunded or revoked (a voided-purchase notification, or not acknowledged in time) | The paid time Google Play gave goes back (unless something paid for later since) | IA `aRefundTakesBack…` | OK-tested |
| 9.21c | Two answers from Google crossing (a notification and the recheck) | The older answer never overwrites the newer one | IA `anOlderAnswerFromGoogle…` | OK-tested |
| 9.22 | Bought but the app never confirmed (closed at the wrong moment) | Found through the salon id in Google's notification; acknowledged. The app also sends it again when the subscription page opens | IA `aPurchaseTheAppNeverConfirmed…` | OK-tested |
| 9.22a | A new purchase after the old one ended, never confirmed by the app | Taken (the old one isn't renewing), acknowledged, paid | IA `aNewPurchaseAfterTheOldOneEnded…` | OK-tested |
| 9.22b | Google's notification and the app's confirm at the same moment | Both end with the purchase once (the salon's row locked); "₹500 received" once | IA `googlesNoticeAndTheAppsConfirm…` | OK-tested |
| 9.23 | A notification lost | Every 5 minutes, the recheck asks Google about purchases not yet acknowledged (after 5 minutes) and those near or past their end (after 6 hours), least recently asked first; an unknown one is not asked again for 6 hours | IA `googleNotTakingTheAcknowledgement…` (unacknowledged); near the end by reading | OK-tested / OK-untested |
| 9.24 | The salon closes or changes hands | Its Google Play purchase stops renewing (tried again until Google takes it) and Google's later notices never bring it back | IA `closingTheSalonStops…` | OK-tested |
| 9.25 | V90 on a database with rows | The salon's row as it was (no purchase, nothing to acknowledge, no token); one salon per purchase; a first report must carry the token | MU `savedBrowsersBecomeWebDevices…AndSalonsGetTheInAppColumns` | OK-tested |
| 9.26 | A PIN code's state | Telangana, Delhi, Maharashtra, Goa, Puducherry…; areas crossing a state line by the full PIN (Moradabad/Kashipur, Saharanpur/Roorkee, Pilibhit/Khatima, Chandigarh/Mohali/Manimajra, Valsad/Daman, Diu; Puducherry's Karaikal, Mahe and Yanam); an unused range: none (ERROR, reported by hand) | PS | OK-tested |
| 9.27 | Many salons at once | Reports in batches of 50 per minute, claimed per row; Google asked outside transactions; one access token shared; each webhook finds the app's subscription by an index | PlayReports, GoogleServiceAccount (by reading); IA `twoServersRunningTheReports…` | OK-tested / OK-untested |
| 9.28 | A Razorpay refund of an in-app payment | Not reported (no refunds by policy) | — | N/A-v1 |

## 10. App updates: screen releases and the update rules (V92)

Tests: `AR` = `AppReleasesIntegrationTest`, `MU` = `MigrationUpgradeIntegrationTest`.

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 10.1 | CI sends a release: start, PUT the zip, publish | 200 `{build, uploadUrl}`; storage has the exact bytes; publish → `LIVE`; the link a phone gets downloads them | Admin: "Live", "Phones get this one" | AR `ciSendsAReleaseAndItGoesLive` | OK-tested |
| 10.2 | Publish before the zip is in storage (CI stopped, PUT failed) | 409 `UPLOAD_MISSING`; stays `UPLOADING` | Admin: "Not finished" | AR same; admin e2e | OK-tested |
| 10.3 | A retried CI step: publish again, or start again before publishing | Publish: 200, nothing changes. Start: 200, the new details, one row | — | AR `ciSendsAReleaseAndItGoesLive`, `aRetriedStartBeforePublishing…` | OK-tested |
| 10.4 | The same build started after it was published | 409 `RELEASE_EXISTS`; never replaced | — | AR `ciSendsAReleaseAndItGoesLive` | OK-tested |
| 10.5 | No token, a wrong token, another app's name | 401 `BAD_RELEASE_TOKEN`; 404 | — | AR `ciNeedsTheAppsOwnToken…` | OK-tested |
| 10.6 | The app's token isn't set on Railway, or is shorter than 32 characters | 503 `RELEASES_OFF` (CI's step fails, nothing stored); a short one (not an empty one) is also logged at startup | — | AR same (customer's short token); the log line by reading | OK-tested |
| 10.7 | Wrong details (build 0, level 0, a version with spaces, a commit that isn't hex, over 50 MB, a short checksum, a session key without `:`) | 400 `VALIDATION_FAILED` naming the field; nothing written | — | AR same | OK-tested |
| 10.8 | Many at once: 8 runs start the same build, then 8 publish it | Every one 200, one row, `LIVE` | — | AR `twoRunsSendingTheSameBuildAtOnceMakeOneRelease` | OK-tested |
| 10.9 | A phone asks: the newest live release its native part can run | Level 1 skips a level-2 release and gets the one before; level 2 gets the level-2 one | App downloads it, switches at the next start | AR `phonesGetTheNewestReleaseTheirAppCanRun` | OK-tested |
| 10.10 | The app came with screens as new as (or newer than) every live release | `bundle: null` (its own) | Nothing changes | AR same | OK-tested |
| 10.11 | The other app's releases | Never offered | — | AR same | OK-tested |
| 10.12 | A Play Store app while no version is marked approved | `bundle: null` | — | AR same | OK-tested |
| 10.13 | A Play Store app newer than the approved version (in Google's review) | `bundle: null`: the reviewer sees what was submitted | — | AR same | OK-tested |
| 10.14 | A test APK (debug) | Always the newest it can run; never `updateRequired` | — | AR same; `anAppOlderThan…` | OK-tested |
| 10.15 | The super admin pulls back the newest release | 200 `PULLED`; phones get the live one before it (or `null`) at once from this copy (other copies: 10.27) | Admin asks first, naming where phones go; then "Pulled back" | AR `aPulledReleaseSendsPhonesBack…`; admin e2e | OK-tested |
| 10.16 | Pull back twice, put back a live one, an unknown build (both ways) | 409 `RELEASE_NOT_LIVE` / `RELEASE_NOT_PULLED`; 404; two at the very same moment: one changes it (`UPDATE … WHERE status = ?`), the other 409 | Admin: the reason, and the list read again | AR same (one after the other); the same moment by reading; admin e2e | OK-tested |
| 10.17 | CI publishes a build the super admin pulled back | 409 `RELEASE_PULLED`; stays pulled | — | AR same | OK-tested |
| 10.18 | Pull-backs and setting changes are logged | `admin_events` APP_RELEASE_PULLED / RESTORED / APP_SETTINGS_CHANGED with who and what | Admin: "pulled back by Prem, <time>" | AR `aPulledReleaseSendsPhonesBack…`, `settingsAreChecked…` | OK-tested |
| 10.19 | An app older than the oldest allowed version | `updateRequired: true` (only Play Store apps; each app its own numbers) | App: only "Please update to continue" | AR `anAppOlderThanTheOldestAllowedMustUpdate`; app unit tests | OK-tested |
| 10.20 | The oldest allowed above the approved version, or set with no approved version | 400 `MIN_ABOVE_STORE` | Admin checks before sending | AR `settingsAreChecked…`; admin e2e | OK-tested |
| 10.21 | Two super admins save the settings | The second: 409 `SETTINGS_CHANGED`; the first stays | Admin shows the other's numbers | AR same; admin e2e | OK-tested |
| 10.22 | A field agent, a support person, a customer, nobody signed in | 403 `SUPER_ADMIN_ONLY`; 403; 401; nothing changes | Admin: no link, the address goes to the dashboard | AR `onlyASuperAdminSees…`; admin e2e | OK-tested |
| 10.23 | The admin page's answers | Never the storage key, checksum or session key | — | AR same | OK-tested |
| 10.24 | Wrong numbers in the check (level 0, versionCode 0, missing) or another app name | 400; 404 | The app keeps its screens | AR `theCheckRefusesWrongNumbers` | OK-tested |
| 10.25 | Many phones at once: every start asks | From memory (no database read); the download link signed once an hour per bundle; the zip from storage, never through the backend | — | AppUpdates, AppBundleFileService (by reading) | OK-untested |
| 10.25a | The database down when a copy starts (no first read yet) | Every phone: `bundle: null, updateRequired: false` (keep your screens), no database call per phone; read again every 30 s | — | AppUpdates.check (by reading) | OK-untested |
| 10.26 | The database can't be read when refreshing | The copy keeps answering from what it had; tries again in 30 s | — | AppUpdates.refresh (by reading) | OK-untested |
| 10.26a | A 30-second read and a pull-back's read at the same time | One at a time (synchronized): the read after the pull-back always lands last, so the pulled build is never offered again from this copy | — | AppUpdates.refresh (by reading) | OK-untested |
| 10.27 | Two copies of the backend: a pull-back on one | The other follows within 30 s (its refresh) | — | (by reading) | OK-untested |
| 10.28 | V92 on a database with rows | Logged team changes stay; both apps' rows with no rules; a build once per app | — | MU `theTeamsLoggedChangesStay…` | OK-tested |
| 10.29 | Old bundles in storage | Kept, so any can be put back (about 1 MB each) | — | — | N/A-v1 |
| 10.30 | A retried CI run: attempt 1's zip uploaded, attempt 2 starts (new checksum) before attempt 1 publishes; attempt 1's PUT lands late | Attempt 2 gets a new storage key; publishing without its zip: 409 `UPLOAD_MISSING`; once sent, LIVE with attempt 2's zip and checksum; attempt 1's late PUT changes nothing phones get | — | AR `aRetriedStartNeverPublishesTheEarlierAttemptsZip` | OK-tested |
| 10.31 | 60 releases for a newer native part after the last one an older app can run | The older app still gets its newest release (20 kept per native level) | — | AR `anOlderAppStillGetsItsNewestReleaseAfterMany…` | OK-tested |
| 10.32 | Storage (R2) down when CI publishes | 503 `SERVICE_BUSY` with Retry-After; CI tries again; stays `UPLOADING` | — | AppBundleFileService.size (by reading) | OK-untested |
| 10.33 | CI re-run of a run that already published (same run number) | 409 `RELEASE_EXISTS`; CI says "already sent" and stays green | — | AR (the 409); the apps' `release-screens.mjs` | OK-tested |
| 10.34 | A super admin forgets to fill in the Play Store version after Google approves | Play Store phones on that version keep their own screens | Admin: a warning while releases are live and no Play Store version is filled in | admin e2e | OK-tested |
| 10.35 | The super admin marks a live release urgent | 200, `urgent`, the note trimmed; phones on older screens get `urgentBuild`; logged once | Admin: "Urgent" with the note; apps: "A fix for the app is ready" | AR `anUrgentRelease…` | OK-tested |
| 10.36 | Marked again, or with a blank or too long note | Again: 200, the first note stays, not logged again. Blank / over 120: 400 `VALIDATION_FAILED` (`note`) | — | AR same | OK-tested |
| 10.37 | Taken back (marked by mistake) | `not-urgent` 200; `urgentBuild` null again | Banner gone at the next check | AR same | OK-tested |
| 10.38 | A pulled-back release marked urgent | 409 `RELEASE_NOT_LIVE` | — | AR same | OK-tested |
| 10.39 | CI sends an `[urgent]` PR's release | `urgentNote` at start; urgent only once published, by nobody on the team | Admin: "from an [urgent] PR" | AR same | OK-tested |
| 10.40 | A phone already on the urgent build's screens (or newer) | `urgentBuild` null (or not above its screens) | No banner | AR same; apps UD | OK-tested |
| 10.41 | A field agent tries to mark urgent | 403 `SUPER_ADMIN_ONLY` | — | AR same | OK-tested |

## 11. Analytics (V93–V95)

Tests: `AN` = `AnalyticsIntegrationTest`, `AP` = `AnalyticsPartsTest`, `SI` = `SubscriptionIntegrationTest`, `IA` =
`InAppSubscriptionIntegrationTest`.

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 11.1 | Anyone but a super admin opens analytics | a customer: 403 `FORBIDDEN` (admin paths); removed from the team with an old token: 404; field agent, support: 403 `SUPER_ADMIN_ONLY`; signed out: 401 | Admin: no link, page refused | AN `onlyASuperAdminSeesAnalytics` | OK-tested |
| 11.2 | Razorpay charges a subscription (website) | one `CHARGE` row: payment id, amount, fee, paid time, period end, `WEBSITE`; test keys: `test` | Money counts it (not test ones) | SI `everyChargeAndRefundIsKept…` | OK-tested |
| 11.3 | The same charge sent again (another event id) | still one row | — | SI same | OK-tested |
| 11.4 | A charge without a payment in the event | paid-until moves; no ledger row (logged) | — | SI same | OK-tested |
| 11.5 | A refund of a subscription payment, sent twice | one `REFUND` row, minus, pointing at the charge; `REFUND_RECORDED` | Money: refunded | SI same | OK-tested |
| 11.6 | A refund of a payment that isn't a subscription's (a kirana order) | not recorded here; handled as before | — | SI same | OK-tested |
| 11.7 | A charge on the subscription made in the Play Store app | `APP_RAZORPAY` | Money: "Razorpay (in the app)" | SI `aChargeOnTheAppsSubscription…` | OK-tested |
| 11.8 | Google Play takes a payment, then revokes it | a `GOOGLE_PLAY` charge (order id, price), dated the start of the month it pays for; then its refund | Money: Google Play, fee estimated | `PaymentImportTest` (date); IA (by reading `PlaySubscriptions.apply`) | OK-tested |
| 11.9 | Past payments before the ledger | imported once (live keys only): every subscription on our plan and every stored one; a test-key id (400/404) skipped; Razorpay busy: the next round goes on where it stopped | — | `PaymentImportTest` | OK-tested |
| 11.10 | Each person counted once a day per app or website, many requests at once | one `daily_activity` row each; written every 30 s | People: active today / 7 / 30 days | AN `eachPersonIsCountedOnce…` | OK-tested |
| 11.11 | A customer sends `admin-web` | not counted as the team | — | AN same | OK-tested |
| 11.12 | A call without a sign-in, or from no Clozest page | not counted | — | AN same | OK-tested |
| 11.13 | Activity older than 35 days | deleted by the hourly job; a salon's last active day stays | Salons: "Last opened …" | AN `oldActivityIsDeleted…` | OK-tested |
| 11.14 | Phones per app version | the first check of the day counted (`first=true`), Play Store and test APK apart, many at once | App versions | AN `appVersionsAreCounted…` | OK-tested |
| 11.15 | The hourly job twice in a row | the same rows (each day replaced whole) | — | AN `theJobWorksOut…` | OK-tested |
| 11.16 | Bookings and visits per day, outcome, hour, pincode; blocks left out; test accounts' signups left out | `analytics_daily` rows | Bookings, People | AN same | OK-tested |
| 11.17 | A range back to front, longer than 366 days, a bad date or pincode, a bad sort | 400 `VALIDATION_FAILED` naming the field | Admin: not sent; a warning | AN same; AP `daysFromTheQuery…` | OK-tested |
| 11.18 | Money per month and source; Google's fee estimated, Razorpay's exact, test payments left out | `GET /money` | Money | AN `moneyIsCounted…`; AP `googlesFeeIsEstimated…` | OK-tested |
| 11.19 | The monthly money now when the last charge was refunded in part, or in full | in part: counted at what it kept; in full: not counted | "Coming in each month now" | AN same | OK-tested |
| 11.20 | Two super admins change Google's rates at once | the second: 409 `SETTINGS_CHANGED`; a rate over 30%: 400 | Admin: "Another super admin changed these…" | AN same | OK-tested |
| 11.21 | A month's CSV with a salon name a spreadsheet would run | the cell starts with '; quoted | Downloaded file | AN same; AP `csvCells…` | OK-tested |
| 11.22 | Where each salon stands: trial, paying by source and renewing, 3 days to pay, hidden, payment problems | `GET /subscriptions` | Subscriptions | AN `subscriptionsShowWhereEachSalonStands` | OK-tested |
| 11.23 | A salon with no Razorpay status | "not renewing" (never unknown), so it shows in "trials ending" | — | AN same | OK-tested |
| 11.24 | Many super admins open the page at once | small reads from the read copy (daily totals, one row per salon); never the users or appointments tables | Each section asks at most every 5 minutes | (by reading) | OK-untested |
| 11.25 | The hourly job on a long history | each day its own transaction with the job statement limit; indexes on users, shops and appointments by time (V94, built without locking) | — | AN; MU | OK-tested |
| 11.26 | A refund that arrives before its charge (the charge's webhook still being retried) | kept waiting; recorded the moment the charge is | Money: refunded | SI `aRefundThatArrivesBeforeItsCharge…` | OK-tested |
| 11.27 | Every state a salon can be in: website / in-app Razorpay, 3 days to pay, hidden, AutoPay over a day late, Razorpay gave up; closed and test salons | counted as the salon's own screen says; late AutoPay and HALTED are payment problems; closed and test salons left out | Subscriptions | AN `everyStateASalonCanBeIn…` | OK-tested |
| 11.28 | Made-up version numbers in update checks | not counted (more than 200 above the newest build known) | App versions | AN `madeUpVersionNumbersAreNeverCounted` | OK-tested |
| 11.29 | A customer sends `partner-app` | not counted as a salon's activity | — | AN `eachPersonIsCountedOnce…` | OK-tested |
| 11.30 | A salon whose Google Play subscription ran out, now paying on the website | counted as WEBSITE | — | (by reading STANDING) | OK-untested |
| 10.42 | The backend just started and hasn't read the releases | 503 `SERVICE_BUSY`, Retry-After; nothing counted | Phones keep their screens | `AppUpdatesBeforeLoadTest` | OK-tested |
| 10.43 | Two super admins mark the same release urgent with different notes | the second: 409 `ALREADY_URGENT`; the first note stays | Admin: says so | AR `anUrgentRelease…` | OK-tested |
| 10.44 | The urgent release needs a newer native part than the phone has | `urgentBuild` null for that phone (its bundle doesn't have the fix); an older urgent release inside the bundle is still announced | — | AR `anUrgentReleaseANewerNativePartNeeds…` | OK-tested |

## 12. Leads: salons found on the map (V96–V98)

Tests: `LI` = `LeadsIntegrationTest` (real Postgres and Redis, a fake Mapbox), `LL` = `LeadsLogicTest`; admin: `LS` =
`e2e/leads.spec.ts`, `LT` = `src/features/leads/logic.test.ts`.

| # | Scenario | API answer | Screen | Test | Verdict |
| --- | --- | --- | --- | --- | --- |
| 12.1 | Anyone but the field team opens Leads | support: 404; a customer: 403 (admin paths); signed out: 401 | Admin: support is sent to Tickets | LI `onlyTheFieldTeamUsesLeads`; LS `support staff…` | OK-tested |
| 12.2 | A field agent asks for the funnel or everyone's follow-ups | 403 `SUPER_ADMIN_ONLY` | No Funnel tab, no "Everyone's" switch | LI same | OK-tested |
| 12.3 | Searching by a city or another name ("hyder", "bangal"), a district ("saharsa"), a post office ("kukat") or a pincode ("500087", "852201") | Cities first, then districts, then areas: an area in a city's district is offered as the city, with `pincode` to tick | Dropdown: "Kukatpally (500072) · Hyderabad", "City · Hyderabad, Medchal Malkajgiri, Ranga Reddy, Sangareddy · 169 areas" | LI `citiesAndDistrictsAreFound…` | OK-tested |
| 12.3a | Every pincode in India | each one is in the table and belongs to a city or district | — | LI `everyPincodeInIndiaIsFound…` | OK-tested |
| 12.3b | A name our list lacks ("Yapral") | `GET /places`: Mapbox's answers with an Indian pincode, once per pincode, as their city or district; one map request counted against the cap; 30/hour per person (429), not counted when refused (MAPBOX_*); 400 q under 3 letters or digits only; MAPBOX_* errors | "Found on the map:" with the place; a look-up per name per session | LI `aPlaceNameOurListLacks…`, `aLookUpTheMapCouldNot…` | OK-tested |
| 12.3c | A city's areas; its funnel | all areas of its districts, its own first, each with its district; 400 kind; 404 for a city name that's a district | Areas show "· Medchal Malkajgiri" | LI `aCityListsTheAreas…` | OK-tested |
| 12.3d | Updating the pincode or city data | Edit the CSV: R__India_places reruns by itself on the next deploy (pincodes updated in place, cities replaced); a broken CSV (wrong column count, duplicate pincode, unknown district) fails the migration and changes nothing (one transaction); a database migrated only part of the way skips it and gets the data when a CSV next changes | — | unit `theCsvReader…`; LI (every test loads it); failure paths by reading | OK-tested |
| 12.3e | India Post locations that are far off | marked doubtful (1,171): a scan uses one only when Mapbox doesn't know the pincode, keeping only businesses that give that pincode; a real far village keeps its point | — | LI `everyPincode…` (500087, 743370); scanner by reading | OK-tested |
| 12.3f | A word inside another ("alwal" / Palwal); a state ("telangana") | post offices match at the start of a word; a state's districts come before areas; areas in a city before villages elsewhere | Alwal (500010) · Hyderabad first | LI `aWordMatchesTheStart…` | OK-tested |
| 12.3g | Old admin website during the deploy | `/districts` without `cities=true`: districts only, area hits as their district | As before | LI `theAdminWebsiteFromBeforeCities…` | OK-tested |
| 12.4 | One letter, or more than 60 typed | empty list; 400 `VALIDATION_FAILED` (q) | Nothing asked under 2 letters | LI same | OK-tested |
| 12.5 | A district that isn't in the list (renamed, typed by hand) | 404 `DISTRICT_NOT_FOUND` | Error with Try again | LI `areasShow…` | OK-tested |
| 12.6 | A field agent's areas | `mine` true on the pincodes they cover | "Yours"; a "My areas" button | LI same; LS first | OK-tested |
| 12.7 | A busy area (more than 25 of a category) | the box searched again as quarters, up to 3 times | All of them listed | LI `aScanFinds…` | OK-tested |
| 12.8 | A mehendi artist, a threading place, a makeup studio not filed as a salon | found by the service words, kept by its name | Listed with its kind | LI same; LL `businessesNotFiled…` | OK-tested |
| 12.9 | Words match a business that isn't a beauty one ("… artist" painter) | left out | — | LI same; LL same | OK-tested |
| 12.10 | A business Mapbox puts in the next pincode | left out of this area (in that area's scan) | — | LI same | OK-tested |
| 12.11 | A business with no pincode on Mapbox | kept, in the scanned area | — | LI same | OK-tested |
| 12.12 | A business that is a Clozest salon (owner's phone, or similar name within 120 m) | `clozest`: name, status; `shopId` for super admins only | "Clozest: Live"; super admin: "Open on Clozest" | LI same; LS first, super admin | OK-tested |
| 12.13 | A Clozest salon no business matched | in `clozestOnly` | Listed under "On Clozest" | LI `aSalonNoBusinessMatched…`; LS first | OK-tested |
| 12.14 | A pincode not in India Post's list, or not a pincode | 404 `AREA_NOT_FOUND` | "This pincode isn't in India Post's list." | LI `aPincodeNobody…` | OK-tested |
| 12.15 | A pincode neither Mapbox nor India Post can place | 404 `AREA_NOT_ON_MAP` | The area is marked; others go on | LS `a search that can't run…` | OK-tested |
| 12.16 | No `MAPBOX_TOKEN` yet | 503 `MAPBOX_OFF` | "Searching the map isn't set up yet…"; search stops | LS same | OK-tested |
| 12.17 | Mapbox refuses the token; Mapbox down and India Post has no point | 503 `MAPBOX_REFUSED`; 503 `MAPBOX_UNAVAILABLE` (Retry-After 60) | "Mapbox refused our key…", search stops; "The map isn't answering…", tried again later | (by reading) | OK-untested |
| 12.18 | The month's cap is reached before or during a scan | before: 429 `MAPBOX_CAP_REACHED`, nothing sent; during: what came back, `capReached` | Search stops, says who raises the cap | LI `theMonthlyCap…` | OK-tested |
| 12.19 | Four scans at once with only 40 requests left | never more than the cap counted or sent | — | LI same | OK-tested |
| 12.20 | Over 60 scans by one person in an hour; a scan refused before searching | 429 `TOO_MANY_REQUESTS` with Retry-After, others unaffected; a refused scan isn't counted | "You have searched many areas this hour…" | LI `tooManyScans…`, `aScanRefusedBefore…` | OK-tested |
| 12.21 | More than 4 scans at once on one server | 503 `SCAN_BUSY`, Retry-After 5 | The area is tried again after the wait | LS same | OK-tested |
| 12.22 | Mapbox fails a request, a box is still full after 3 splits, or a scan passes 60 s (postcode lookup and waiting for a turn included) | what came back, `partial` | "The map didn't answer fully for … search again later" | (by reading `LeadScanner`) | OK-untested |
| 12.23 | Every request counted for the month; the token never in an answer or a log | `mapbox_usage` = requests made | "Map searches this month: n of cap" | LI `aScanFinds…` | OK-tested |
| 12.24 | Saving a lead not contacted yet (a note, a follow-up) | no name, phone or point kept | — | LI `aLeadIsSaved…` | OK-tested |
| 12.25 | Marking it called, then back to not contacted; no answer | name, phone, point kept; back to `NEW` 400 (status); `NO_ANSWER` kept, not counted as contacted, no WhatsApp consent | "No answer" chip | LI same | OK-tested |
| 12.26 | Saving the same thing twice | the lead as it is, no new version or history | — | LI same | OK-tested |
| 12.27 | Someone saved it meanwhile (checked before anything else, so a lead they tried isn't refused as "back to not contacted") | 409 `LEAD_CHANGED` with the lead now | Shows what they saved, keeps your typing, Save again | LI `anOldVersion…`; LS `a lead someone else saved…` | OK-tested |
| 12.28 | Six saves of a new lead at once; six of one version at once | one wins, the rest 409 | — | LI same | OK-tested |
| 12.29 | A field agent takes someone else's lead, or gives one away; the assignee left the team | 403 `ASSIGNED_TO_SOMEONE_ELSE`; they can still change its status and note; a leaver's lead is free to take | "(only a super admin can give it to someone else)" | LI `agentsTakeFreeLeads…`, `aSignedUpLeadKeeps…`; LS `a lead given to someone else…` | OK-tested |
| 12.30 | Assigning a lead to someone not on the field team | 400 `VALIDATION_FAILED` (assignedTo) | Picker lists only the field team | LI same | OK-tested |
| 12.31 | Bad status, note over 500, a new past follow-up (an overdue one kept is fine: LI `anOverdueFollowUp…`), unknown pincode, contacted without a name, bad phone | 400 naming the field | The sheet says what to fix | LI `badFieldsAreNamed` | OK-tested |
| 12.32 | WhatsApp consent before contacting, or without a mobile | 400 (whatsappOk) | Switch off with the reason | LI same; LS first | OK-tested |
| 12.33 | Consent recorded, then the lead changed by someone else; then the phone changed | the first time and person kept; a new or removed phone ends the consent (history: phone, WhatsApp), ticked again on a later save | "Recorded … by Ravi" | LI `whatsappConsent…` | OK-tested |
| 12.34 | A contacted business registers on Clozest later (same phone); one already on Clozest is called | the job ties it once, the assigned person credited, history "signed up", its status frozen (409 `LEAD_SIGNED_UP`); a salon registered before the first contact (or closed) is never credited | "Signed up from this lead"; funnel | LI `aContactedLead…Credit`, `aSalonAlreadyOnClozest…`, `aSignedUpLeadKeeps…` | OK-tested |
| 12.35 | Two leads match one salon | only one is tied (unique) | — | (by reading `LeadStore.link`) | OK-untested |
| 12.36 | Follow-ups due: mine, past ones first; a super admin sees everyone's | due today or earlier, not signed up | Follow-ups tab and the home card | LI `followUpsDue…`; LS first | OK-tested |
| 12.37 | A contacted lead Mapbox no longer lists | shown from its saved details, `onMapbox` false | "saved by the team (not on the map now)" | LI `aContactedLeadMapbox…`; LS super admin | OK-tested |
| 12.38 | Many people scanning and saving at once | scans bounded per server; Mapbox requests counted in one statement; leads saved under a row lock with versions | — | LI cap and saves | OK-tested |
| 12.39 | INCONVENIENCE: results vanish on reload | Mapbox's terms: not stored | Kept while the tab stays open; the team's own work (status, notes) is always saved | — | N/A-v1 |
| 12.41 | A field agent wants the leads they work on without searching areas | `GET /admin/leads/mine`: theirs, tried, not signed up | My leads in the Follow-ups tab | LI `myLeadsList…` | OK-tested |
| 12.40 | INCONVENIENCE: a big city spans districts | Cities (india-cities.csv) cover all their districts | "Hyderabad" gives the whole city; each area says its district | LI `aCityListsTheAreas…` | OK-tested |
