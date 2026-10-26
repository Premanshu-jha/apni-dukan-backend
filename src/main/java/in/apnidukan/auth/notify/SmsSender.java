package in.apnidukan.auth.notify;

/** Sends a text message. Today: printed in the console. After DLT registration: MSG91. */
public interface SmsSender {

    void send(String phone, String message);
}
