package in.apnidukan.auth.domain;

/**
 * Who may use the admin website (users.admin_role). ADMIN: the field team (shop visits, support). SUPER_ADMIN: also
 * adds and removes admins and runs OTP test mode; there is at most one, set by hand in the database.
 */
public enum AdminRole {
    NONE,
    ADMIN,
    SUPER_ADMIN;

    public boolean isAdmin() {
        return this != NONE;
    }
}
