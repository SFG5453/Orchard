/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package main

import (
	"context"
	"fmt"
	"os/exec"
	"path/filepath"
	"strings"
)

func (i *installer) openRelease(ctx context.Context, version string) error {
	manifest, err := i.fetchManifest(ctx)
	if err != nil {
		return err
	}
	var selected *release
	for index := range manifest.Releases {
		if manifest.Releases[index].Version == version {
			selected = &manifest.Releases[index]
			break
		}
	}
	if selected == nil {
		return fmt.Errorf("Orchard %s is not present in the package manifest", version)
	}
	target, err := detectTarget()
	if err != nil {
		return err
	}
	installDirectory, runtimeDirectory, _, err := installPaths(target, version, selected.ElectronVersion)
	if err != nil {
		return err
	}
	if err := validateInstallation(installDirectory, version, target); err != nil {
		return fmt.Errorf("Orchard %s is not installed", version)
	}
	if !runtimeReady(runtimeDirectory, target) {
		return fmt.Errorf("Electron %s is not installed", selected.ElectronVersion)
	}

	launcher := filepath.Join(installDirectory, "orchard")
	var command *exec.Cmd
	if strings.HasPrefix(target, "win32-") {
		launcher = filepath.Join(installDirectory, "orchard.cmd")
		command = exec.Command("cmd", "/c", launcher)
	} else {
		command = exec.Command(launcher)
	}
	command.Dir = installDirectory
	if err := command.Start(); err != nil {
		return fmt.Errorf("could not open Orchard: %w", err)
	}
	return command.Process.Release()
}
