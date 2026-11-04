package in.apnidukan.common.notify;

import java.util.Map;

/**
 * Sends one SMS from a DLT-approved template (MSG91 Flow). Implemented by the auth module, which owns the MSG91
 * account; used by the notifications module for booking messages.
 */
public interface TextMessages {

    /**
     * @param phone      10-digit Indian mobile number
     * @param templateId the MSG91 template id
     * @param variables  the template's variables by name
     * @return true if MSG91 accepted it; false if it refused or couldn't be reached (try again later)
     */
    boolean send(String phone, String templateId, Map<String, String> variables);
}
