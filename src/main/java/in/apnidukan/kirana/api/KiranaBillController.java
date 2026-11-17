package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.KiranaRequests.ChangePayment;
import in.apnidukan.kirana.api.KiranaRequests.NewBill;
import in.apnidukan.kirana.api.KiranaRequests.NewReturn;
import in.apnidukan.kirana.api.KiranaRequests.VoidBill;
import in.apnidukan.kirana.api.KiranaResponses.BillPage;
import in.apnidukan.kirana.api.KiranaResponses.BillResult;
import in.apnidukan.kirana.api.KiranaResponses.BillView;
import in.apnidukan.kirana.api.KiranaResponses.CancelResult;
import in.apnidukan.kirana.api.KiranaResponses.ChangeResult;
import in.apnidukan.kirana.api.KiranaResponses.DaySummary;
import in.apnidukan.kirana.api.KiranaResponses.ReturnResult;
import in.apnidukan.kirana.service.BillService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Walk-in bills at the kirana's counter (owner and helpers). Cancelling, returns and payment changes: the owner any
 * time, the helper who made the bill within 10 minutes. See BillService.
 */
@RestController
@RequestMapping("/api/v1/kirana")
public class KiranaBillController {

    private final BillService bills;
    private final CurrentUserProvider currentUser;

    public KiranaBillController(BillService bills, CurrentUserProvider currentUser) {
        this.bills = bills;
        this.currentUser = currentUser;
    }

    /** Saves a bill: 201 with the stock after it; 200 with the first bill when this clientRef was saved before. */
    @PostMapping("/bills")
    public ResponseEntity<BillResult> create(@Valid @RequestBody NewBill body) {
        BillResult r = bills.create(me(), body);
        return ResponseEntity.status(r.repeated() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    /** The shop's bills, newest first, 50 at a time. */
    @GetMapping("/bills")
    public BillPage list(@RequestParam(required = false) String cursor) {
        return bills.list(me(), cursor);
    }

    @GetMapping("/bills/{id}")
    public BillView get(@PathVariable UUID id) {
        return bills.get(me(), id);
    }

    /** Cancels a bill: stock back, khata reversed. 409 BILL_VOIDED if already cancelled; BILL_HAS_RETURNS. */
    @PostMapping("/bills/{id}/void")
    public CancelResult cancel(@PathVariable UUID id, @Valid @RequestBody VoidBill body) {
        return bills.cancel(me(), id, body);
    }

    /** Changes how a bill was paid; the bill keeps its number. 409 KHATA_LIMIT / BILL_VOIDED / BILL_HAS_RETURNS. */
    @PostMapping("/bills/{id}/payment")
    public ChangeResult changePayment(@PathVariable UUID id, @Valid @RequestBody ChangePayment body) {
        return bills.changePayment(me(), id, body);
    }

    /** Items of a bill brought back: 201; 200 with the first one when this clientRef was returned before. */
    @PostMapping("/bills/{id}/returns")
    public ResponseEntity<ReturnResult> returnItems(@PathVariable UUID id, @Valid @RequestBody NewReturn body) {
        ReturnResult r = bills.returnItems(me(), id, body);
        return ResponseEntity.status(r.repeated() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    /** One India day's totals (today without date): bills by payment, khata payments received. */
    @GetMapping("/day")
    public DaySummary day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                          LocalDate date) {
        return bills.day(me(), date);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
