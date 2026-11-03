package in.apnidukan.shops.domain;

/**
 * OWNER can do everything in the shop; HELPER can bill and see stock (see the LLD's roles). BARBER is a salon's
 * barber with his own sign-in: he sees and works his own day only.
 */
public enum ShopRole {
    OWNER,
    HELPER,
    BARBER
}
