package in.apnidukan.common.audit;

import in.apnidukan.common.audit.domain.Revision;
import in.apnidukan.common.security.AuthPrincipal;
import org.hibernate.envers.RevisionListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Stamps each history entry with the signed-in person (from the access token) who made the change. */
public class RevisionUserListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal who) {
            ((Revision) revisionEntity).setUserId(who.userId());
        }
    }
}
