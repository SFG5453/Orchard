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
	"archive/zip"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

func electronArchive(version, target string) (string, string) {
	name := fmt.Sprintf("electron-v%s-%s.zip", version, target)
	base := fmt.Sprintf("https://github.com/electron/electron/releases/download/v%s/", version)
	return base + name, name
}

func (i *installer) fetchElectronChecksum(ctx context.Context, version, archiveName string) (string, error) {
	url := fmt.Sprintf("https://github.com/electron/electron/releases/download/v%s/SHASUMS256.txt", version)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	response, err := i.client.Do(request)
	if err != nil {
		return "", fmt.Errorf("could not fetch Electron checksums: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return "", fmt.Errorf("Electron checksums returned HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 4*1024*1024))
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 2 && strings.TrimPrefix(fields[1], "*") == archiveName && sha256Pattern.MatchString(fields[0]) {
			return fields[0], nil
		}
	}
	return "", fmt.Errorf("Electron's official checksums do not contain %s", archiveName)
}

func electronExecutable(directory, target string) string {
	switch {
	case strings.HasPrefix(target, "win32-"):
		return filepath.Join(directory, "electron.exe")
	case strings.HasPrefix(target, "darwin-"):
		return filepath.Join(directory, "Electron.app", "Contents", "MacOS", "Electron")
	default:
		return filepath.Join(directory, "electron")
	}
}

func runtimeReady(directory, target string) bool {
	info, err := os.Stat(electronExecutable(directory, target))
	return err == nil && !info.IsDir()
}

func extractZip(ctx context.Context, archivePath, destination string) error {
	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		return err
	}
	defer reader.Close()
	for _, entry := range reader.File {
		if err := ctx.Err(); err != nil {
			return err
		}
		if !isSafeArchivePath(entry.Name) {
			return fmt.Errorf("unsafe path in Electron archive: %s", entry.Name)
		}
		output := filepath.Join(destination, filepath.FromSlash(entry.Name))
		if entry.FileInfo().IsDir() {
			if err := os.MkdirAll(output, 0o755); err != nil {
				return err
			}
			continue
		}
		if !entry.Mode().IsRegular() {
			return fmt.Errorf("unsupported entry in Electron archive: %s", entry.Name)
		}
		if err := os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
			return err
		}
		input, err := entry.Open()
		if err != nil {
			return err
		}
		mode := entry.Mode().Perm()
		if mode == 0 {
			mode = 0o644
		}
		file, err := os.OpenFile(output, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
		if err != nil {
			input.Close()
			return err
		}
		_, copyErr := io.Copy(file, &contextReader{ctx: ctx, reader: input})
		closeErr := file.Close()
		inputErr := input.Close()
		if copyErr != nil {
			return copyErr
		}
		if closeErr != nil {
			return closeErr
		}
		if inputErr != nil {
			return inputErr
		}
	}
	return nil
}

func writeLauncher(directory, target, electronVersion string) error {
	var name, contents string
	switch {
	case strings.HasPrefix(target, "win32-"):
		name = "orchard.cmd"
		contents = fmt.Sprintf("@echo off\r\n\"%%~dp0..\\..\\runtimes\\electron\\%s\\%s\\electron.exe\" \"%%~dp0\" %%*\r\n", electronVersion, target)
	case strings.HasPrefix(target, "darwin-"):
		name = "orchard"
		contents = fmt.Sprintf("#!/bin/sh\napp_root=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\nexec \"$app_root/../../runtimes/electron/%s/%s/Electron.app/Contents/MacOS/Electron\" \"$app_root\" \"$@\"\n", electronVersion, target)
	default:
		name = "orchard"
		contents = fmt.Sprintf("#!/bin/sh\napp_root=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\nexec \"$app_root/../../runtimes/electron/%s/%s/electron\" --disable-setuid-sandbox \"$app_root\" \"$@\"\n", electronVersion, target)
	}
	return os.WriteFile(filepath.Join(directory, name), []byte(contents), 0o755)
}
