# Try the API from IntelliJ

1. Start the app (▶ on `ApniDukanApplication`).
2. Create `http/http-client.private.env.json`. Git ignores it, so your number and codes stay private:
   ```json
   { "local": { "phone": "98XXXXXXXX", "email": "you@gmail.com", "otp": "", "emailCode": "" } }
   ```
3. Open `api.http`, pick the **local** environment at the top, and click ▶ next to a request.

- After **1. Send OTP**, put the code from your SMS into `"otp"` in the private file, then run **2. Verify OTP**.
  Sign-in saves the tokens automatically, so every later request is already signed in.
- After **5. Send an email code**, put the code from your inbox into `"emailCode"`, then run **6**.
- **7. Upload** saves the new file's id automatically for requests 8 and 9.
