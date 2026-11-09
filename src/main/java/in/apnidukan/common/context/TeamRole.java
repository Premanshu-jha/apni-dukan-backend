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
    /** Everything: every business, assignments, the team, support, test mode. */
    SUPER_ADMIN;

    public boolean onTeam() {
        return this != NONE;
    }
}
