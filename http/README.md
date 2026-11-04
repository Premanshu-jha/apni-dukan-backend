# Try the API from IntelliJ

1. Start the app (▶ on `ApniDukanApplication`).
2. Create `http/http-client.private.env.json`. Git ignores it, so your number and codes stay private:
   ```json
   { "local": { "phone": "98XXXXXXXX", "email": "you@gmail.com", "otp": "", "emailCode": "",
                "barberPhone": "97XXXXXXXX", "bookingDate": "2026-11-20" } }
   ```
3. Open `api.http`, pick the **local** environment at the top, and click ▶ next to a request.

- After **1. Send OTP**, put the code from your SMS into `"otp"` in the private file, then run **2. Verify OTP**.
  Sign-in saves the tokens automatically, so every later request is already signed in.
- After **5. Send an email code**, put the code from your inbox into `"emailCode"`, then run **6**.
- **7. Upload** saves the new file's id automatically for requests 8 and 9.
- **Salon, start to end:** 6b-salon, 4 (Refresh), 6e-6k (services, a barber, his week), 11-11d (photos, Submit for
  visit), then the field team approves with 11e-11f (`users.admin = true` on their account; 11e saves the shop's id
  and version for 11f). After that 13-13d book as a customer, 14-14f work the day as the salon, and 15-16e try moves, no-shows, breaks,
  closed days and switching a barber off. `bookingDate` is an India
  date within the salon's booking window.

