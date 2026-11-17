package in.apnidukan.kirana.api;

import in.apnidukan.common.context.CurrentUserProvider;
import in.apnidukan.kirana.api.KiranaRequests.EditProduct;
import in.apnidukan.kirana.api.KiranaRequests.NewBarcode;
import in.apnidukan.kirana.api.KiranaRequests.NewProduct;
import in.apnidukan.kirana.api.KiranaRequests.StockChange;
import in.apnidukan.kirana.api.KiranaResponses.ProductDetail;
import in.apnidukan.kirana.api.KiranaResponses.ProductPage;
import in.apnidukan.kirana.api.KiranaResponses.ProductView;
import in.apnidukan.kirana.api.KiranaResponses.ScanView;
import in.apnidukan.kirana.service.ProductService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The kirana's catalogue and stock for the partner app (the shop's owner and helpers; 409 NOT_A_KIRANA for a salon).
 * See ProductService for every check.
 */
@RestController
@RequestMapping("/api/v1/kirana/products")
public class KiranaProductController {

    private final ProductService products;
    private final CurrentUserProvider currentUser;

    public KiranaProductController(ProductService products, CurrentUserProvider currentUser) {
        this.products = products;
        this.currentUser = currentUser;
    }

    /**
     * The catalogue in name order, 50 at a time; q: names containing it, or barcodes starting with it; cursor: the
     * previous page's next.
     */
    @GetMapping
    public ProductPage list(@RequestParam(required = false) String q, @RequestParam(required = false) String cursor) {
        return products.list(me(), q, cursor);
    }

    /** Products at or below their "running out" alert (or below zero). */
    @GetMapping("/running-out")
    public List<ProductView> runningOut() {
        return products.runningOut(me());
    }

    /**
     * The product with this barcode and how many of it one scan means (a scan at the counter). 404 PRODUCT_NOT_FOUND:
     * offer to link it.
     */
    @GetMapping("/barcode/{code}")
    public ScanView byBarcode(@PathVariable String code) {
        return products.byBarcode(me(), code);
    }

    /** A product with its latest 50 stock changes. */
    @GetMapping("/{id}")
    public ProductDetail detail(@PathVariable UUID id) {
        return products.detail(me(), id);
    }

    /** Adds a product: 201. 409 NAME_TAKEN / BARCODE_TAKEN. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody NewProduct body) {
        return products.create(me(), body);
    }

    /** Changes a product (owner only). 409 PRODUCT_CHANGED for a stale version. */
    @PatchMapping("/{id}")
    public ProductView update(@PathVariable UUID id, @Valid @RequestBody EditProduct body) {
        return products.update(me(), id, body);
    }

    /** Links a barcode (owner or helper), with how many one scan means. 409 BARCODE_TAKEN / TOO_MANY_BARCODES. */
    @PostMapping("/{id}/barcodes")
    public ProductView addBarcode(@PathVariable UUID id, @Valid @RequestBody NewBarcode body) {
        return products.addBarcode(me(), id, body);
    }

    /** Unlinks a barcode (owner only). 404 BARCODE_NOT_FOUND. */
    @DeleteMapping("/{id}/barcodes/{code}")
    public ProductView removeBarcode(@PathVariable UUID id, @PathVariable String code) {
        return products.removeBarcode(me(), id, code);
    }

    /** Stock that came in, damaged pieces, or a count by hand. */
    @PostMapping("/{id}/stock")
    public ProductView stock(@PathVariable UUID id, @Valid @RequestBody StockChange body) {
        return products.changeStock(me(), id, body);
    }

    private UUID me() {
        return currentUser.get().userId();
    }
}
