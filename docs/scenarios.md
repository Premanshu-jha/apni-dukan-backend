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
