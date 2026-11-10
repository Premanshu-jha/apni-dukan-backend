package in.apnidukan.salon.service;

import in.apnidukan.salon.api.SalonResponses.CatalogService;

import java.util.List;

/**
 * The common services a salon ticks at registration (GET /salon/service-catalog), each with a suggested time and
 * come-back interval. The owner sets each price and may change the rest; anything else he types in as his own
 * service. Suggestions only: nothing here is stored or checked against later.
 */
final class ServiceCatalog {

    /** In the order the app lists them: the everyday ones first. */
    static final List<CatalogService> SERVICES = List.of(
            new CatalogService("Haircut", 30, 21),
            new CatalogService("Beard trim", 15, 7),
            new CatalogService("Haircut and beard", 45, 21),
            new CatalogService("Clean shave", 20, 7),
            new CatalogService("Kids haircut", 20, 30),
            new CatalogService("Women's haircut", 45, 42),
            new CatalogService("Hair wash", 15, 0),
            new CatalogService("Head massage", 20, 14),
            new CatalogService("Hair colour", 60, 30),
            new CatalogService("Beard colour", 30, 21),
            new CatalogService("Hair spa", 45, 30),
            new CatalogService("Hair straightening", 120, 90),
            new CatalogService("Facial", 45, 30),
            new CatalogService("Face clean-up", 30, 21),
            new CatalogService("Threading", 15, 14),
            new CatalogService("Waxing", 45, 28),
            new CatalogService("Manicure", 30, 28),
            new CatalogService("Pedicure", 45, 28));

    private ServiceCatalog() {
    }
}
