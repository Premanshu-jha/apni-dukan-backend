package in.apnidukan.auth.domain;

/**
 * Who may use the admin website (users.admin_role). ADMIN: has a designation (AdminDesignation, V30), today always a
 * field agent, who visits only the businesses assigned to them. SUPER_ADMIN: everything: the team, assignments,
 * support and OTP test mode. There may be several; the first is set by hand in the database.
 */
public enum AdminRole {
    NONE,
    ADMIN,
    SUPER_ADMIN;

    public boolean isAdmin() {
        return this != NONE;
    }
}
