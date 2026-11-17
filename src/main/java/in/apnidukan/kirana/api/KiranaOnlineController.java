package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.OrderRequests.Fssai;
import in.apnidukan.kirana.api.OrderRequests.TakingOrders;
import in.apnidukan.kirana.api.OrderResponses.CatalogueView;
import in.apnidukan.kirana.api.OrderResponses.OnlineView;
import in.apnidukan.kirana.service.CatalogueService;
import in.apnidukan.kirana.service.OnlineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A kirana's online orders, its own side (owner and helpers): whether customers can order now, the owner's switch,
 * the FSSAI licence, earnings and payouts; and a scan's details from the shared catalogue. See OnlineService and
 * CatalogueService.
 */
@RestController
@RequestMapping("/api/v1/kirana")
public class KiranaOnlineController {

    private final OnlineService online;
    private final CatalogueService catalogue;
    private final CurrentUserProvider currentUser;

    public KiranaOnlineController(OnlineService online, CatalogueService catalogue, CurrentUserProvider currentUser) {
        this.online = online;
        this.catalogue = catalogue;
        this.currentUser = currentUser;
    }

    /** Whether customers can order now, what stops it, the FSSAI check, earnings and payouts (owner). */
    @GetMapping("/online")
    public OnlineView get() {
        return online.get(me());
    }

    /** The owner's switch. 409 NOT_READY naming what is missing. */
    @PutMapping("/online/taking-orders")
    public OnlineView takingOrders(@Valid @RequestBody TakingOrders body) {
        return online.setTakingOrders(me(), body.on());
    }

    /** The owner adds or replaces the FSSAI licence: checked by the AI within minutes. */
    @PutMapping("/online/fssai")
    public OnlineView fssai(@Valid @RequestBody Fssai body) {
        return online.setFssai(me(), body);
    }

    /** A barcode's details in the shared catalogue, to fill in a new product. 404 CATALOGUE_NOT_FOUND. */
    @GetMapping("/catalogue/barcode/{code}")
    public CatalogueView byBarcode(@PathVariable String code) {
        return catalogue.byBarcode(me(), code);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
