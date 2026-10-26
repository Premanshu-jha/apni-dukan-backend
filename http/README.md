# Try the API from IntelliJ

1. Start the app (▶ on `ApniDukanApplication`).
2. Create `http/http-client.private.env.json` (ignored by Git, so your number stays private):
   ```json
   { "local": { "phone": "98XXXXXXXX", "email": "you@gmail.com" } }
   ```
3. Open `api.http`, pick the **local** environment at the top, and click ▶ next to a request.

Sign-in saves the tokens automatically, so every later request is already signed in.
After "Send OTP", copy the code from your SMS into `@otp` at the top of the file.
