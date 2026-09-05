/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

package main

import (
	"context"
	"fmt"
	"os"
)

func (i *installer) uninstallRelease(ctx context.Context, version string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !versionPattern.MatchString(version) {
		return fmt.Errorf("invalid Orchard version: %s", version)
	}
	target, err := detectTarget()
	if err != nil {
		return err
	}
	installDirectory, _, _, err := installPaths(target, version, "unused")
	if err != nil {
		return err
	}
	if err := validateInstallation(installDirectory, version, target); err != nil {
		return fmt.Errorf("Orchard %s is not installed: %w", version, err)
	}
	if err := os.RemoveAll(installDirectory); err != nil {
		return fmt.Errorf("could not uninstall Orchard %s: %w", version, err)
	}
	return nil
}
