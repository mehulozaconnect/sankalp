# Sankalp (संकल्प)

Tasks, habits, focus and insights in one calm app. Works on phone, laptop and as a Home Screen app.

## Put it online (GitHub Pages)

1. On GitHub, create a new repository called `sankalp` (Public).
2. Click **Add file → Upload files** and upload everything in this folder:
   `index.html`, `manifest.json`, `sw.js`, `icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `README.md`.
3. Go to **Settings → Pages**. Under *Branch*, pick `main` and `/ (root)`, then **Save**.
4. After a minute the app is live at `https://mehulozaconnect.github.io/sankalp/`.

## Install on your phone

- **Android (Chrome):** open the link, tap the ⋮ menu, then **Add to Home screen** (or **Install app**).
- **iPhone (Safari, iOS 16.4+):** open the link, tap Share, then **Add to Home Screen**.
- Open it from the Home Screen, go to **Settings → Reminders → Turn on**, and allow notifications.

## Typing tasks

Write it like a message. Any order works, commas are optional.

| You type | You get |
|---|---|
| `Call mom today 5pm p2, home` | Today 5 PM, priority 2, project Home, reminder at 5 PM |
| `Call mom kal 5pm p2 home` | Same, for tomorrow |
| `Send proposal fri 4pm p1 sales 45m` | Friday 4 PM, P1, Sales, 45 minutes |
| `Gym every mon, wed, fri 7am` | Repeats Mon, Wed, Fri at 7 AM |
| `Pay bill 15 oct remind 1h before` | 15 Oct 9 AM, reminder at 8 AM |
| `Meet Rahul kal shaam 6 baje` | Tomorrow 6 PM, in Evening |
| `Read a book someday` | Someday list |
| `Team review tomorrow evening #client-demos @calls` | Tomorrow 7 PM, project and tag |

Words it understands: today/aaj, tomorrow/kal, parso, mon to sun, next week, weekend, 15 oct, 15/10, in 3 days, someday/kabhi,
5pm, 17:30, 6 baje, morning/subah, evening/shaam, tonight/raat, p1 to p4, daily/roz, weekdays, every mon, every 2 weeks, monthly,
@tag, #project, 30m, 1h, remind 15m before. A project name typed after a comma (or after a date/time) is picked up without `#`.
A new `#name` creates the project.

Gestures: swipe a task right to finish it, left to reschedule. On a laptop press **N** to add a task.

## Reminders

Reminders, morning summary, habit reminders and meeting heads-ups show as phone notifications with **Done** and **Snooze 10 min** buttons.
In this version they fire while Sankalp is open or running in the background. Keep it installed on the Home Screen and do not swipe it away from recent apps.
On Android, if reminders arrive late, set Chrome's battery usage to *Unrestricted*.

For reminders that fire even when the app is fully closed, the next step is a small push service (Firebase + a free scheduler). It needs your own Firebase account, so it is set up separately.

## Connect Outlook (one-time, about 5 minutes)

Sankalp reads your meeting times and titles (read-only) through Microsoft's official sign-in.

1. Go to https://portal.azure.com and sign in with your Microsoft account.
2. Search **App registrations → New registration**.
   - Name: `Sankalp`
   - Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**
   - Redirect URI: platform **Single-page application (SPA)**, value `https://mehulozaconnect.github.io/sankalp/`
3. Click **Register**, then copy the **Application (client) ID**.
4. Open Sankalp → **Settings → Outlook calendar**, paste the ID and tap **Connect Outlook**.

For a company Outlook account, your IT admin may need to approve the app once (the sign-in screen will say so).

## Your data

Everything is saved on the device in the browser's storage. Use **Settings → Download backup** now and then and keep the file in Google Drive. **Restore backup** brings it back on a new phone or laptop.
