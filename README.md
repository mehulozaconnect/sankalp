# Sankalp 2.2

Tasks, habits, focus, birthdays and insights in one calm app. Phone, laptop and Home Screen.

## Update your GitHub site

Upload these files to your `sankalp` repository (Add file, Upload files) and replace the old ones:
`index.html`, `sw.js`, `manifest.json`, `icon-192.png`, `icon-512.png`, `icon-maskable-512.png`, `README.md`.
Commit. Open the app twice (or close and reopen it) to load the new version. Your tasks stay; they are upgraded automatically.

## Typing tasks

| You type | You get |
|---|---|
| `Call dad daily at 5.30 pm start tomorrow till 31 dec` | Repeats every day from tomorrow to 31 Dec, reminder 5:30 PM |
| `Gym every mon, wed, fri 7am kal se 30 nov tak` | Mon/Wed/Fri 7 AM, from tomorrow until 30 Nov |
| `Pay bill 15 oct remind 1 day before` | Reminder on 14 Oct (9 AM when no time is set) |
| `Pay rent 1st nov remind 2 days before remind 1h before` | Two reminders |
| `Send proposal fri 4pm p1 sales pin` | Friday 4 PM, P1, Sales, pinned to the top |
| `meet Client call 3pm-4pm` | A meeting block on your day bar, not a task |
| `Read a book someday` | Someday list |

Also understood: today/aaj, tomorrow/kal, parso, next week, 15 oct, 25/12, in 3 days, end of month, morning/subah, evening/shaam,
6 baje, p1 to p4, weekdays, every 2 weeks, monthly, yearly, @tag, #project, 30m, 1h. A project name after a comma is picked up without `#`.

## Sync phone and laptop (one-time, about 10 minutes, personal Gmail)

1. Open https://console.firebase.google.com with your **personal Gmail** and click **Create a project**. Name it `sankalp`. Google Analytics: off.
2. **Build, Authentication, Get started.** Choose **Google**, switch it on, pick your Gmail as support email, Save.
   Then **Authentication, Settings, Authorized domains, Add domain**: `mehulozaconnect.github.io`
3. **Build, Firestore Database, Create database.** Location: `asia-south1 (Mumbai)`. Start in **production mode**.
   Open the **Rules** tab, replace everything with the rules below, and click **Publish**:

   ```
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       match /users/{uid}/{doc=**} {
         allow read, write: if request.auth != null && request.auth.uid == uid;
       }
     }
   }
   ```
4. **Project settings** (gear icon), **Your apps**, click the **</>** (Web) icon, name it `sankalp`, Register.
   Copy the whole `const firebaseConfig = { ... }` block.
5. In Sankalp: **Settings, Sync phone and laptop**, paste it, tap **Save and sign in**, choose your Gmail.
6. Do step 5 on your other device with the same Gmail.

After that, ticking a task on your phone shows up on your laptop within seconds, and the other way round. The free Firebase plan is far more than one person needs.

## Google Calendar, live (personal Gmail, read-only)

Uses the same Firebase project as Sync, so do the Sync steps first.

1. Open https://console.cloud.google.com, sign in with your **personal Gmail**, and pick the `sankalp` project at the top.
2. **APIs & Services, Library**, search **Google Calendar API**, click **Enable**.
3. **APIs & Services, OAuth consent screen** (may be called **Google Auth Platform**). If asked, choose **External**, app name `Sankalp`, your Gmail as support and contact email. Under **Audience** (or **Test users**) add your own Gmail.
4. **APIs & Services, Credentials.** Open the **Web client (auto created by Google Service)**. Under **Authorized JavaScript origins** add `https://mehulozaconnect.github.io`. Save.
5. Copy the **Client ID** from that page (ends with `.apps.googleusercontent.com`).
6. In Sankalp: **Settings, Google Calendar**, paste it, tap **Connect Google Calendar**, choose your Gmail, allow.
   Google may show "Google hasn't verified this app": tap **Continue**. It is your own app.

Events from the past week and the next 45 days show on your day bar, calendar, meeting reminders and Insights. Sankalp refreshes them when you open it and every 10 minutes.
Google keeps a web app signed in for one hour at a time; after that, Today shows **Tap to refresh** and one tap reconnects.

## Meetings, privately

Nothing signs in to your company account.

- Outlook on your laptop: **File, Save Calendar**, choose the next 30 days, Save. In Sankalp: **Settings, Meetings, Import calendar file**. Re-import whenever your week changes; old entries are replaced. Teams links show a **Join** button.
- During the day: type `meet Client call 3pm-4pm`.
- Sankalp nudges you on Monday mornings to refresh.

## Bring tasks from other apps

Settings, **Bring your tasks over**:

- **Todoist:** Settings, Backups, download the latest backup (.zip). Or export one project as CSV. Sections become tags, sub-tasks become subtasks.
- **TickTick:** web, Settings, Account, Generate backup (.csv). Folders become areas, lists become projects.
- **Notion:** open the database, three dots, Export, Markdown & CSV. Upload the .csv or the .zip.
- **Things 3:** select the to-dos, copy, paste into the box. Project names on their own line become projects.

Tasks already in Sankalp with the same name and project are skipped, so importing twice is safe.

## Reminders

Phone notifications with **Done** and **Snooze 10 min** buttons, for tasks, habits, birthdays, meetings, a morning plan and an evening wrap-up.
Quiet hours and the Focus shield hold back everything except P1.

- **Remind again until done:** if you miss a reminder, it repeats every 10 minutes (change it in Settings, or per task under Reminders: off, 5, 10, 15 or 30 min) until you tap **Done** or **Snooze**, up to 12 times. The notification stays on screen until you act on it.
- **Subtask reminders:** on a task's page, tap the bell next to any subtask and set a date and time. Done on that notification ticks only the subtask.
- **Only on some days:** `Gym every mon, wed, fri 7am` reminds you on those days only.
They fire while Sankalp is open or running in the background. Keep it on your Home Screen and do not swipe it away.
Alarm-style reminders when the app is fully closed come with the Android version.

## Task page

Tap any task to open its page: date with how many days overdue, repeat, reminders, priority, project, subtasks,
notes with **bold**, highlight, headings, lists and links, and images from your camera or gallery.
The **⋯** menu has Focus, Make most important, Duplicate, Save as template, Undo changes and Delete.

## Your data

Saved on the device (and in your own Firebase if sync is on). **Settings, Download backup** saves a file (images included) you can keep in Google Drive. Images sync between devices when Sync is on.
