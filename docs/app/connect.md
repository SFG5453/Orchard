---
title: Orchard Connect
summary: Play music on your phone from the computer, or on the computer from your phone, with one shared queue, and let the computer do the heavy work for the phone.
group: Basics
icon: cast
keywords:
  - orchard connect
  - connect
  - cast
  - play on phone
  - play on another device
  - remote control
  - control from phone
  - switch device
  - transfer playback
  - devices
  - controlled by
  - mix host
  - apple music on computer
platforms:
  - desktop
order: 65
---

# Orchard Connect

Orchard Connect lets one device control playback on another. Your computer can play music on your phone, and your phone can play music on your computer. One device plays the music (the target). The other shows the same song, queue, and position and sends your actions to it (the controller).

## What Orchard Connect needs

- An Orchard account, signed in on both devices. Sign in under **Settings**, **General**, **Orchard Account**. See [Accounts and startup](accounts.md).
- The same Orchard account on both devices.
- An up-to-date Orchard on both devices. A device with an older Orchard shows "Update Orchard on this device to use Connect" and cannot be selected.
- An internet connection on both devices. The devices find each other through your Orchard account, so they do not need to be on the same Wi-Fi.

Your YouTube Music and Apple Music sign-ins never leave the device they are on. Connect does not copy passwords or tokens between devices.

## Open Orchard Connect

Select the cast button in the player bar, next to the volume control. Its tooltip reads "Orchard Connect". The **Orchard Connect** window opens and lists **This computer** and every other device signed in to your Orchard account.

Each device row shows what it is doing, for example "Playing Song name", "Paused on Song name", or "Idle".

If the window says "Sign in to your Orchard account in Settings to play on your other devices.", sign in to an Orchard account first. If it says "No other devices are online. Open Orchard on your phone and sign in to the same Orchard account.", open Orchard on the other device.

## Play music on another device

1. Select the cast button in the player bar.
2. Select the device in the **Orchard Connect** window.

The row reads "Connecting…" and then "Connected on this network" or "Connected over the internet". The cast button lights up, and the bitrate area of the player bar shows "On" and the device name.

What plays depends on which device was already playing:

- If the other device is playing, it keeps playing, and this computer shows its song and queue. This computer pauses its own music.
- If the other device is idle and this computer is playing, the music moves to the other device. It starts at the same song and position, with the same queue, shuffle, and repeat. This computer stops playing.
- If neither device is playing, nothing starts.

This choice happens once, when you connect. A dropped connection that reconnects does not move the music again.

## Control the other device

While connected, use the player bar as usual. Play, pause, seek, next, previous, shuffle, repeat, volume, the queue, and playing a song, album, or playlist all happen on the other device. This computer does not play any sound.

The volume slider sets the other device's volume.

## Switch playback back to this computer

1. Select the cast button in the player bar.
2. Select **This computer**. While you control another device, its row reads "Switch back to this computer".

This computer stops controlling the other device. Music on the other device keeps playing until you pause it there.

## When your phone controls this computer

When your phone connects to this computer, the **This computer** row reads "Controlled by" and the phone's name, and the cast button lights up. The computer keeps playing through its own speakers, and the phone shows the same song and queue.

The phone's notification, lock screen, and other media controls (Bluetooth headphones, a watch, Android Auto) also show the computer's song. Play, pause, next, previous, and seeking from them control the computer.

The phone can start Apple Music songs on the computer when both devices are connected to Apple Music. If the computer uses YouTube Music as its music source, it switches to Apple Music when the first Apple Music song arrives. If the computer is not connected to Apple Music, the phone shows "Sign in to Apple Music on the other device to play Apple Music songs there."

If you close Orchard on the phone, the music keeps playing on the computer.

## What the computer does for a connected phone

When your phone plays music and this computer is connected to it, the computer helps with work that is slow on a phone:

- **Adaptive mix**: the phone sends both songs to the computer, and the computer analyses them and renders the blend. The phone still plays it, at the right moment on its own clock. If the computer disconnects or fails, the phone prepares the mix itself. See [Crossfade and gapless playback](crossfade.md).
- **Artwork**: the computer looks up album artwork for the phone.

The phone keeps control of playback, volume, and its speakers. The computer only helps.

## Connect messages and what to do

- "The other device runs a different Orchard version. Update both devices to use Connect.": update Orchard on both devices.
- "Device name is offline.": open Orchard on that device and check its internet connection.
- "Device name is already controlling another device.": on that device, switch back to itself first.
- "Orchard Connect is offline. Check your connection.": this computer cannot reach your Orchard account. Check the internet connection.
- "Lost the connection to Device name.": the devices could not reconnect within 30 seconds. Select the device again.
- "Device name stopped controlling this computer.": the controller left. Music keeps playing here.
- "Device name could not be verified. Sign in to Orchard on both devices.": sign in to the same Orchard account on both devices.
- "Device name left Orchard Connect.": Orchard closed on that device.

While a connection drops and comes back, the device row reads "Reconnecting…". Playback continues on the target the whole time.
