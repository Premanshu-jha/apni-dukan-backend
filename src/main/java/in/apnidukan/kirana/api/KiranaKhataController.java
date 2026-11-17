package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.KiranaRequests.AdvanceBack;
import in.apnidukan.kirana.api.KiranaRequests.EditKhataCustomer;
import in.apnidukan.kirana.api.KiranaRequests.KhataPayment;
import in.apnidukan.kirana.api.KiranaRequests.NewKhataCustomer;
import in.apnidukan.kirana.api.KiranaRequests.ReversePayment;
import in.apnidukan.kirana.api.KiranaResponses.KhataCustomerView;
import in.apnidukan.kirana.api.KiranaResponses.KhataDetail;
import in.apnidukan.kirana.api.KiranaResponses.KhataPage;
import in.apnidukan.kirana.api.KiranaResponses.PaymentResult;
import in.apnidukan.kirana.service.KhataService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The kirana's khata book: regular customers, what they owe, their payments. See KhataService. */
@RestController
@RequestMapping("/api/v1/kirana/khata")
public class KiranaKhataController {

    private final KhataService khata;
    private final CurrentUserProvider currentUser;

    public KiranaKhataController(KhataService khata, CurrentUserProvider currentUser) {
        this.khata = khata;
        this.currentUser = currentUser;
    }

    /** The book in name order, 50 at a time, with the total owed; owing=true: only those who owe. */
    @GetMapping
    public KhataPage list(@RequestParam(required = false) String q,
                          @RequestParam(defaultValue = "false") boolean owing,
                          @RequestParam(required = false) String cursor) {
        return khata.list(me(), q, owing, cursor);
    }

    /** A customer with their entries, newest first (cursor: older ones). */
    @GetMapping("/{id}")
    public KhataDetail detail(@PathVariable UUID id, @RequestParam(required = false) String cursor) {
        return khata.detail(me(), id, cursor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KhataCustomerView create(@Valid @RequestBody NewKhataCustomer body) {
        return khata.create(me(), body);
    }

    @PatchMapping("/{id}")
    public KhataCustomerView update(@PathVariable UUID id, @Valid @RequestBody EditKhataCustomer body) {
        return khata.update(me(), id, body);
    }

    /**
     * A payment received: 201; 200 with the first one when this clientRef was recorded before. More than they owe:
     * 409 MORE_THAN_OWED unless advance is true.
     */
    @PostMapping("/{id}/payments")
    public ResponseEntity<PaymentResult> pay(@PathVariable UUID id, @Valid @RequestBody KhataPayment body) {
        PaymentResult r = khata.pay(me(), id, body);
        return ResponseEntity.status(r.repeated() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    /**
     * Gives a customer's advance back (owner only): 201; 200 with the first when this clientRef was given before. 409
     * MORE_THAN_ADVANCE.
     */
    @PostMapping("/{id}/advance-back")
    public ResponseEntity<PaymentResult> giveBack(@PathVariable UUID id, @Valid @RequestBody AdvanceBack body) {
        PaymentResult r = khata.giveBack(me(), id, body);
        return ResponseEntity.status(r.repeated() ? HttpStatus.OK : HttpStatus.CREATED).body(r);
    }

    /**
     * Undoes a payment recorded by mistake (the owner; the helper who recorded it within 10 minutes): 201 with the
     * reversal. 409 PAYMENT_REVERSED.
     */
    @PostMapping("/{id}/payments/{entryId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResult reverse(@PathVariable UUID id, @PathVariable UUID entryId,
                                 @Valid @RequestBody ReversePayment body) {
        return khata.reverse(me(), id, entryId, body);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
