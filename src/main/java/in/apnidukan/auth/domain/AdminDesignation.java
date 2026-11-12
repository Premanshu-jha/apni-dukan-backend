package in.apnidukan.auth.domain;

/**
 * An ADMIN's job title (users.admin_designation, V30); a SUPER_ADMIN has none. It decides what the admin may do on
 * the admin website. FIELD_AGENT: visits the businesses assigned to them (by the pincodes they cover) and approves
 * or sends back only those. SUPPORT (V46): answers the support tickets assigned to them, and nothing else (no
 * pincodes). More may come later, each with its own allowed actions.
 */
public enum AdminDesignation {
    FIELD_AGENT,
    SUPPORT
}
