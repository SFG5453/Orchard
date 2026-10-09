---
title: Accounts and startup
summary: Manage the YouTube Music and Orchard accounts, startup restore, the window size, the system tray, and listening history.
group: Customize
icon: circle-user-round
keywords:
  - account
  - sign out
  - sign in
  - switch account
  - brand account
  - channel
  - orchard account
  - youtube music account
  - devices
  - github
  - startup
  - restore queue
  - restore page
  - last page
  - system tray
  - close to tray
  - listening history
platforms:
  - desktop
order: 120
---

# Accounts and startup

Settings, General holds the startup behavior, the system tray option, listening history, and both accounts. Orchard has two separate accounts: a YouTube Music account that supplies your music, and an optional Orchard account that links your devices.

## Sign out of YouTube Music

1. Open **Settings**.
2. Select **General**.
3. In the **YouTube Music** card, select **Sign out**.

Orchard closes Settings and signs you out of YouTube Music. The card shows your YouTube account name and handle while you are signed in. To sign back in, see [Getting started](getting-started.md).

## Switch to another YouTube account or channel

Switch accounts to use another YouTube channel, such as a brand account, or another Google account, without signing out first.

1. Open **Settings**, then **General**.
2. In the **YouTube Music** card, select **Switch account**.
3. Pick an account or channel in the "Choose a YouTube account" window.

The window closes and Orchard loads the home page, library, and playlists of the account you picked. The current song keeps playing. While the window is open, the button reads **Choosing...**. Close the window to keep the current account.

Orchard checks that the selected channel can load your YouTube Music library before switching. If that check fails, your current account stays active and Settings shows the error.

If Orchard says "That account needs a fresh sign-in. Sign out, then sign in with it.", the account you picked uses a different Google login than the one Orchard holds. Select **Sign out**, then sign in with that account.

## Sign in to an Orchard account

An Orchard account lets you use Orchard across your devices. It is separate from your YouTube Music account and optional. Animated artwork in Discord also needs it. See [Integrations](integrations.md).

1. Open **Settings**, then **General**.
2. In the **Orchard Account** card, select **Sign in with Google**.
3. Finish signing in in your browser. The card says "Finish signing in in your browser."

Select **Open browser again** if the browser window closed. Select **Cancel** to stop signing in.

## Link GitHub to the Orchard account

The **Orchard Account** card has a **GitHub** row while you are signed in. Select **Link GitHub** and approve Orchard in your browser. The row then shows your GitHub username and "Used for bug reports". Select **Unlink** to remove it. Reports need this link; see [Report a bug](report-a-bug.md).

## Sign out of the Orchard account

Select **Sign out** in the **Orchard Account** card, under Settings, General.

## See and remove devices on your Orchard account

The **Orchard Account** card lists a **Devices** section while you are signed in to an Orchard account. Each row shows the device name and its platform (Linux, Windows, macOS, Android, iOS). The current computer is labeled "This device". Other devices show "Last seen" with a date and a **Remove** button. Select **Remove** to take a device off the account.

## Restore the queue, song, and page when Orchard starts

The **Launch** group has a row named "Restore last session" with a switch (accessible name "Save queue, current song, and page"). Its description reads "Pick up the last queue, song, position, and page when Orchard starts." It is on by default. With the switch on, Orchard restores the last queue, song, and playback position when it starts. It also reopens the last page you were on: Library, Search with its text and filter, or an album, artist, or playlist. Home is the default page. Orchard restores the page after you sign in. Back from a restored album, artist, or playlist goes to Home.

Turn the switch off to start Orchard with an empty player on the Home page. Turning it off also deletes the saved page.

## Remember the window size

The **Launch** group has a row named "Remember window size" with a switch. Its description reads "Reopen Orchard at the size you last used it." It is on by default. With the switch on, Orchard reopens at the last window size, and maximized if it was maximized when you quit. The window position is left to your desktop. With the switch off, Orchard opens at its default size.

## Keep Orchard playing in the system tray

The **Launch** group has a row named "Keep playing in the system tray" with a switch (accessible name "Close to system tray"). Its description reads "Closing the window leaves Orchard running in the tray." It is off by default. The row appears only on systems that have a system tray.

With the switch on, closing the window while signed in hides it and the music keeps playing. Opening Orchard again shows the existing window. During sign in, closing the window quits Orchard. The tray menu holds the exit command. With the switch off, closing the window quits Orchard.

## Add played songs to your YouTube Music history

The **YouTube Music** group has a row named "Save plays to YouTube Music history" with a switch (accessible name "Send listening history to YouTube"). Its description reads "Songs you play in Orchard are added to your YouTube Music history." It is on by default. Turn it off to keep Orchard plays out of your YouTube Music history.
