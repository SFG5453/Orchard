/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

#include "app/Bootstrapper.hpp"
#include "platform/Platform.hpp"

// Small program, big job title: installer, updater, repair tool, rollback
// manager and launcher. Mostly it just gets out of the way.
int main(int argc, char **argv) {
  orchard::boot::platform::initProcess();
  return orchard::boot::Bootstrapper(orchard::boot::platform::arguments(argc, argv)).run();
}
