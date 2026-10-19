# Darb setup

Three one-time jobs that need your own accounts. Darb keeps working without them; each one switches on more features.

| Job | Unlocks | Time |
| --- | --- | --- |
| 1. GitHub secret | Signed APK published to Releases | 1 min |
| 2. Supabase project | Live traffic and shared reports | 5 min |
| 3. Google sign-in | Users can sign in to share traffic and reports | 10 min |

---

## 1. GitHub secret (APK signing)

The APK is signed with a key stored in this repo **encrypted** (`android/release.keystore.enc`). GitHub needs its password.

1. Repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**
2. Name: `DARB_KEYSTORE_PASSWORD`
3. Value: the password Claude gave you in chat (keep a copy somewhere safe — without it, future APKs can't install as updates)
4. Repo → **Actions** → **Android APK** → **Run workflow**

Each run publishes a new release. The latest APK is always at:
https://github.com/07x01313480/darb/releases/latest/download/darb.apk

**Installing:** open that link on the phone, or copy `darb.apk` to a USB stick for a car head unit, then open it with a file manager and allow installing from that source. New versions install over the old one and keep your settings.

---

## 2. Supabase (free tier)

1. Go to [supabase.com](https://supabase.com) → **New project**. Pick a region close to Iraq (Frankfurt works well) and save the database password somewhere.
2. **SQL Editor** → **New query** → paste the whole of [`supabase/schema.sql`](supabase/schema.sql) → **Run**.
3. **Authentication** → **URL Configuration**
   - **Site URL:** `https://07x01313480.github.io/darb/`
   - **Redirect URLs** → add both:
     - `https://07x01313480.github.io/darb/`
     - `darb://auth`
4. Copy two values and send them to Claude:
   - **Project URL** — shown in the **Connect** dialog, looks like `https://abcdefgh.supabase.co`
   - **Publishable key** — **Settings** → **API Keys**, starts with `sb_publishable_`

The publishable key is designed to be public; the database only allows the specific actions in `schema.sql`. Never share the **secret** key.

---

## 3. Google sign-in

### In Google Cloud

1. [console.cloud.google.com](https://console.cloud.google.com) → create a project named **Darb**.
2. **Google Auth Platform** → **Branding**: app name `Darb`, your support email, developer contact email. Save.
3. **Audience**: **External**, then **Publish app** so anyone can sign in (while in "Testing", only listed test users can). Darb only asks for name, email and profile picture.
4. **Clients** → **Create client** → **Web application**
   - **Authorized JavaScript origins:** `https://07x01313480.github.io`
   - **Authorized redirect URIs:** the callback URL from Supabase (next section), like `https://abcdefgh.supabase.co/auth/v1/callback`
   - **Create**, then copy the **Client ID** and **Client secret**.

### In Supabase

1. **Authentication** → **Sign In / Providers** → **Google**
2. Copy the **Callback URL** shown there into the Google client above.
3. Paste the Google **Client ID** and **Client secret**, turn Google **on**, **Save**.

---

## What happens next

Once Claude has the Supabase Project URL and publishable key, they go into `index.html` (`SB_URL`, `SB_KEY`). After the push:

- the website updates within a couple of minutes
- GitHub builds a new APK with the same settings

Sign in from **Settings → Sign in with Google**. Signing in is optional; without it, Darb still shows other drivers' traffic and reports.
