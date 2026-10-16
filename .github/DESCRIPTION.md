<p align="center"><img src="https://raw.githubusercontent.com/R-Besson/Thumbstick/main/.github/icon.png" width="128" alt="Thumbstick logo"></p>
<h1 align="center">Thumbstick</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.7.10-62B47A?logo=minecraft&logoColor=white" alt="Minecraft 1.7.10">
  <img src="https://img.shields.io/badge/Forge-10.13.4.1614-E04E14" alt="Forge">
  <img src="https://img.shields.io/badge/side-client%20only-blue" alt="Client only">
</p>

<p align="center">A Controller Support Mod for Minecraft Forge 1.7.10.</p>

---

**ℹ️ Note:** Thumbstick is **client side only**. It does nothing on servers, and servers don't need it.

## Features

- **Analog movement and camera** with a radial dead zone and an ease in look curve
- **Works in every menu, modded ones too.** A controller cursor acts as the mouse and buttons become clicks
- **Chat keyboard:** dpad right opens chat with an on screen keyboard
- **Inventory controls:** take, split and quick move, slot snapping, dpad slot navigation, creative tabs and scrolling
- **Rebindable buttons**, and any key binding (vanilla or modded) can get a controller button
- **Button hints** for every control, shown only while the controller is in use. Size, corner, detail and layout are configurable
- **Built in layouts** for Xbox, DualShock 4, DualSense and Switch Pro controllers, plus a **Controller Mapping** screen to remap any gamepad
- **Hot plugging** with connect and disconnect notifications
- Six cursor styles and auto detected button icons

## Settings

Open **Options → Controls → Controller Settings...** (or **Mods → Thumbstick → Config**). From there you can pick the controller, tune sensitivity, edit **Button Bindings** and run **Controller Mapping**.

**💡 Tip:** If buttons are wrong or a toast says *Unknown layout*, run **Controller Mapping**. Click any input in the list and press it on the controller. It works with any gamepad.

## Compatibility

- Minecraft Forge **1.7.10**, no dependencies
- Input goes through **JInput**, which ships with Minecraft, so no native libraries are bundled
- Gameplay actions press the vanilla key bindings, so other mods see controller input as normal key presses

**⚠️ Linux:** JInput needs read access to `/dev/input/event*`. Without it no controller is found.

## Credits

Inspired by [Controllable](https://github.com/MrCrayfish/Controllable) by MrCrayfish. Button icons and cursors come from it (MIT License).

Source code and issues on [GitHub](https://github.com/R-Besson/Thumbstick).
