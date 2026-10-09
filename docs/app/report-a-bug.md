---
title: Report a bug
summary: Send a bug, idea, or feedback as a GitHub issue with a screenshot, and get told in Orchard when someone replies, a commit mentions it, or it closes.
group: Help
icon: bug
keywords:
  - report a bug
  - bug report
  - feedback
  - feature request
  - idea
  - support
  - github
  - link github
  - screenshot
  - capture
  - issue
  - diagnostics
  - system details
platforms:
  - desktop
order: 155
---

# Report a bug

Reports go to the Orchard GitHub repository as public issues. Orchard watches each issue and tells you when a maintainer replies, a commit mentions it, or it closes. Reporting needs an Orchard account and a linked GitHub account.

## Open the report window

Use any of these:

- Select the bug button in the top bar, left of the Docs button.
- Open **Settings** and select **Report a bug** under **Docs** in the sidebar.
- Press **/** or **Ctrl+K** and pick **Report a bug** in Spotlight.

The window lists your reports on the left and a **New report** form on the right.

## Link your GitHub account

Reports need a linked GitHub account, so the issue is attributed to you and maintainers can ask you questions on GitHub.

1. Sign in to your Orchard account. See [Accounts and startup](accounts.md).
2. Open the report window, or open **Settings**, **General**, and find the **GitHub** row in the **Orchard Account** card.
3. Select **Link GitHub**.
4. Approve Orchard on the GitHub page that opens in your browser.

Orchard notices the link on its own; the browser tab says "GitHub linked". Orchard reads only your GitHub username and avatar and does not keep a GitHub token. To remove the link, select **Unlink** in the **GitHub** row.

If GitHub says "This GitHub account is linked to a different Orchard account.", sign in to that Orchard account, or unlink GitHub there first.

## Send a report

1. Pick **Bug**, **Idea**, or **Feedback**.
2. Enter a short title.
3. Describe what happened and what you expected. Steps that make it happen again help the most.
4. Add a screenshot if it helps. See the next section.
5. Select **Send report**.

The report opens in the window after it is sent. **Discard** clears the form. Orchard limits reports to 5 per hour and 20 per day.

## Attach a screenshot of the problem

When the report window opens, Orchard snapshots the screen you were on. The **The screen you were on** box shows it dimmed. It is not sent unless you select **Attach this screen**.

To capture a different screen:

1. Select **Capture a screen** or **Capture another screen**.
2. The report window hides and a bar appears: "Open the screen with the problem, then capture."
3. Go to the page, player, or panel that shows the problem.
4. Select **Capture**. The bar hides itself before the capture, so it is not in the picture.

The report window comes back with your text intact and the screenshot attached. **Choose image...** attaches an image file instead. **Remove** drops the screenshot.

Screenshots are posted publicly with the issue. Check the preview for your account name or anything else private before you send.

## Choose whether to include system details

**Include system details** is on by default. It adds the Orchard version, operating system, kernel, processor architecture, Qt version, graphics API, window and screen size, language, and the page you were on. It never includes songs, playlists, or account details. Select **Show** to read exactly what is sent.

## See replies and fixes

Select a report in the list to see its activity, newest at the bottom:

- Replies, with a **Maintainer** badge for project maintainers.
- Commits and pull requests that mention the report.
- Closing, with the reason, such as "Fixed by commit abc1234" or "Closed as not planned".
- Reopening and assignment.

A green dot on the bug button in the top bar and next to a report means something new happened. Orchard checks every 5 minutes and when you come back to the window, and shows a notice like "“Crash on start”: Fixed by commit abc1234". Opening the report clears the dot.

To reply, select **Open on GitHub** and comment on the issue there.

## Fix "Link your GitHub account to send reports"

Your GitHub link was removed, or never finished. Select **Link GitHub** in the report window and approve Orchard in the browser.

## Fix "GitHub did not accept the report. Try again in a moment."

GitHub was unreachable or refused the issue. Your draft is kept. Wait a minute, then select **Send report** again.
