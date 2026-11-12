package in.apnidukan.common.context;

/**
 * What someone may do on the admin website, for modules other than sign-in (UserDirectory.teamRole). Read from the
 * database, never from the sign-in token.
 */
public enum TeamRole {
    /** Not on the team (or the account is deleted). */
    NONE,
    /** An ADMIN whose designation is field agent: only the businesses assigned to them. */
    FIELD_AGENT,
    /**
     * An ADMIN whose designation is support: only the support tickets (the helpdesk module). Never the businesses,
     * assignments or the team: {@link #onTeam()} is false for them, so every page that checks it refuses them.
     */
    SUPPORT,
    /** Everything: every business, assignments, the team, support, test mode. */
    SUPER_ADMIN;

    /** The field team, who may see businesses: field agents and super admins (not support people). */
    public boolean onTeam() {
        return this == FIELD_AGENT || this == SUPER_ADMIN;
    }

    /** Who may work on support tickets: support people and super admins. */
    public boolean handlesTickets() {
        return this == SUPPORT || this == SUPER_ADMIN;
    }
}
