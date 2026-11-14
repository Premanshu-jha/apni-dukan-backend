package in.apnidukan.auth.service;

import in.apnidukan.auth.notify.EmailSender;
import in.apnidukan.auth.repo.UserRepository;
import in.apnidukan.common.notify.TeamEmails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link TeamEmails}: to each super admin's verified email, through the sign-in codes' email provider. */
@Component
public class TeamEmailSender implements TeamEmails {

    private static final Logger log = LoggerFactory.getLogger(TeamEmailSender.class);

    private final UserRepository users;
    private final EmailSender email;

    public TeamEmailSender(UserRepository users, EmailSender email) {
        this.users = users;
        this.email = email;
    }

    @Override
    public int superAdminRecipients() {
        return users.findSuperAdminEmails().size();
    }

    @Override
    public Sent toSuperAdmins(String subject, String body) {
        List<String> to = users.findSuperAdminEmails();
        int sent = 0;
        for (String address : to) {
            try {
                email.send(address, subject, body);
                sent++;
            } catch (RuntimeException e) {   // the provider down or refusing: the others are still tried
                log.warn("Team email \"{}\" not sent to one super admin: {}", subject, e.getClass().getSimpleName());
            }
        }
        if (to.isEmpty()) {
            log.warn("Team email \"{}\" not sent: no super admin has a verified email", subject);
        }
        return new Sent(to.size(), sent);
    }
}
