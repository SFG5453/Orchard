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
	"archive/tar"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/klauspost/compress/zstd"
)

type installProgress struct {
	Phase   string  `json:"phase"`
	Message string  `json:"message"`
	Detail  string  `json:"detail,omitempty"`
	Percent float64 `json:"percent"`
}

type installResult struct {
	Version     string `json:"version"`
	Target      string `json:"target"`
	InstallPath string `json:"installPath"`
}

type progressReporter func(installProgress)

type installer struct {
	baseURL           string
	githubReleasesURL string
	client            *http.Client
}

func newInstaller() *installer {
	return &installer{baseURL: packageBaseURL, githubReleasesURL: githubReleasesAPIURL, client: http.DefaultClient}
}

func formatBytes(bytes int64) string {
	units := [...]string{"B", "KB", "MB", "GB"}
	value := float64(bytes)
	unit := 0
	for value >= 1024 && unit < len(units)-1 {
		value /= 1024
		unit++
	}
	if unit == 0 || value >= 100 {
		return fmt.Sprintf("%.0f %s", value, units[unit])
	}
	return fmt.Sprintf("%.1f %s", value, units[unit])
}

func (i *installer) fetchManifest(ctx context.Context) (packageManifest, error) {
	return i.fetchManifestFrom(ctx, i.baseURL)
}

func (i *installer) fetchManifestFrom(ctx context.Context, baseURL string) (packageManifest, error) {
	return i.fetchManifestURL(ctx, baseURL+"manifest.json")
}

func (i *installer) fetchManifestURL(ctx context.Context, manifestURL string) (packageManifest, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return packageManifest{}, err
	}
	request.Header.Set("Accept", "application/json")
	response, err := i.client.Do(request)
	if err != nil {
		return packageManifest{}, fmt.Errorf("could not reach the Orchard package service: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return packageManifest{}, fmt.Errorf("the Orchard package service returned HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 4*1024*1024))
	if err != nil {
		return packageManifest{}, err
	}
	return parseManifest(data)
}

type githubRelease struct {
	Draft      bool `json:"draft"`
	Prerelease bool `json:"prerelease"`
	Assets     []struct {
		Name               string `json:"name"`
		BrowserDownloadURL string `json:"browser_download_url"`
	} `json:"assets"`
}

func latestBetaManifestURL(releases []githubRelease) string {
	for _, candidate := range releases {
		if candidate.Draft || !candidate.Prerelease {
			continue
		}
		for _, asset := range candidate.Assets {
			if asset.Name != "manifest.json" {
				continue
			}
			parsed, err := url.Parse(asset.BrowserDownloadURL)
			if err == nil && parsed.Scheme == "https" && parsed.Hostname() == "github.com" {
				return parsed.String()
			}
		}
	}
	return ""
}

func (i *installer) fetchAvailableManifest(ctx context.Context) (packageManifest, error) {
	stableManifest, err := i.fetchManifest(ctx)
	if err != nil {
		return packageManifest{}, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, i.githubReleasesURL, nil)
	if err != nil {
		return packageManifest{}, err
	}
	request.Header.Set("Accept", "application/vnd.github+json")
	request.Header.Set("User-Agent", "Orchard-Packages")
	response, err := i.client.Do(request)
	if err != nil {
		return packageManifest{}, fmt.Errorf("could not reach GitHub releases: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return packageManifest{}, fmt.Errorf("GitHub releases returned HTTP %d", response.StatusCode)
	}
	var releases []githubRelease
	if err := json.NewDecoder(io.LimitReader(response.Body, 4*1024*1024)).Decode(&releases); err != nil {
		return packageManifest{}, fmt.Errorf("GitHub releases could not be read: %w", err)
	}
	manifestURL := latestBetaManifestURL(releases)
	if manifestURL == "" {
		return packageManifest{}, fmt.Errorf("no Orchard beta package release is available on GitHub")
	}
	betaManifest, err := i.fetchManifestURL(ctx, manifestURL)
	if err != nil {
		return packageManifest{}, fmt.Errorf("could not read the latest GitHub beta manifest: %w", err)
	}
	available := packageManifest{SchemaVersion: 1}
	for _, candidate := range stableManifest.Releases {
		if candidate.Channel == "stable" {
			available.Releases = append(available.Releases, candidate)
		}
	}
	for _, candidate := range betaManifest.Releases {
		if candidate.Channel == "beta" {
			available.Releases = append(available.Releases, candidate)
		}
	}
	return available, nil
}

func (i *installer) installRelease(ctx context.Context, version string, report progressReporter) (installResult, error) {
	report(installProgress{Phase: "manifest", Message: "Reading the release manifest…", Percent: 2})
	manifest, err := i.fetchAvailableManifest(ctx)
	if err != nil {
		return installResult{}, err
	}
	var selected *release
	for index := range manifest.Releases {
		if manifest.Releases[index].Version == version {
			selected = &manifest.Releases[index]
			break
		}
	}
	if selected == nil {
		return installResult{}, fmt.Errorf("Orchard %s is not present in the package manifest", version)
	}
	assetBaseURL := releaseBaseURL(*selected)
	if selected.Channel == "beta" {
		githubManifest, err := i.fetchManifestFrom(ctx, assetBaseURL)
		if err != nil {
			return installResult{}, fmt.Errorf("could not read Orchard %s from its GitHub release: %w", version, err)
		}
		selected = nil
		for index := range githubManifest.Releases {
			if githubManifest.Releases[index].Version == version && githubManifest.Releases[index].Channel == "beta" {
				selected = &githubManifest.Releases[index]
				break
			}
		}
		if selected == nil {
			return installResult{}, fmt.Errorf("Orchard %s is not present in its GitHub release manifest", version)
		}
	}
	target, err := detectTarget()
	if err != nil {
		return installResult{}, err
	}
	native, ok := selected.Native[target]
	if !ok {
		return installResult{}, fmt.Errorf("Orchard %s has no native package for %s", version, target)
	}
	installDirectory, runtimeDirectory, cacheDirectory, err := installPaths(target, version, selected.ElectronVersion)
	if err != nil {
		return installResult{}, err
	}
	sharedName, err := archiveName(selected.Shared, assetBaseURL)
	if err != nil {
		return installResult{}, err
	}
	nativeName, err := archiveName(native, assetBaseURL)
	if err != nil {
		return installResult{}, err
	}

	sessionDirectory := filepath.Join(cacheDirectory, "sessions", randomID())
	installParent := filepath.Dir(installDirectory)
	stagingDirectory := filepath.Join(installParent, ".orchard.staging-"+randomID())
	runtimeStagingDirectory := filepath.Join(filepath.Dir(runtimeDirectory), ".electron.staging-"+randomID())
	sharedPath := filepath.Join(sessionDirectory, sharedName)
	nativePath := filepath.Join(sessionDirectory, nativeName)
	if err := os.MkdirAll(sessionDirectory, 0o755); err != nil {
		return installResult{}, err
	}
	defer os.RemoveAll(sessionDirectory)
	defer os.RemoveAll(stagingDirectory)
	defer os.RemoveAll(runtimeStagingDirectory)
	if err := os.MkdirAll(installParent, 0o755); err != nil {
		return installResult{}, err
	}
	if err := os.MkdirAll(filepath.Dir(runtimeDirectory), 0o755); err != nil {
		return installResult{}, err
	}
	_ = os.RemoveAll(stagingDirectory)

	totalBytes := selected.Shared.Size + native.Size
	var downloadedBefore int64
	download := func(asset packageAsset, destination, label string) error {
		assetURL, err := resolveAssetURL(asset, assetBaseURL)
		if err != nil {
			return err
		}
		err = i.downloadFile(ctx, assetURL, destination, asset.Size, func(downloaded int64) {
			ratio := float64(downloadedBefore+downloaded) / float64(totalBytes)
			report(installProgress{
				Phase: "download-" + label, Message: "Downloading " + label + " package…",
				Detail: formatBytes(min(downloaded, asset.Size)) + " of " + formatBytes(asset.Size), Percent: 5 + ratio*40,
			})
		})
		if err == nil {
			downloadedBefore += asset.Size
		}
		return err
	}

	if err := download(selected.Shared, sharedPath, "common"); err != nil {
		return installResult{}, err
	}
	if err := download(native, nativePath, "native"); err != nil {
		return installResult{}, err
	}
	report(installProgress{Phase: "verify-shared", Message: "Verifying common package…", Percent: 50})
	if err := verifySHA256(sharedPath, selected.Shared.SHA256); err != nil {
		return installResult{}, err
	}
	report(installProgress{Phase: "verify-native", Message: "Verifying native package…", Percent: 54})
	if err := verifySHA256(nativePath, native.SHA256); err != nil {
		return installResult{}, err
	}
	if !runtimeReady(runtimeDirectory, target) {
		runtimeURL, runtimeName := electronArchive(selected.ElectronVersion, target)
		runtimePath := filepath.Join(sessionDirectory, runtimeName)
		report(installProgress{Phase: "electron-checksum", Message: "Reading Electron's official checksum…", Percent: 57})
		expectedChecksum, err := i.fetchElectronChecksum(ctx, selected.ElectronVersion, runtimeName)
		if err != nil {
			return installResult{}, err
		}
		report(installProgress{Phase: "download-electron", Message: "Downloading the official Electron runtime…", Percent: 59})
		if err := i.downloadFile(ctx, runtimeURL, runtimePath, 0, func(downloaded int64) {
			report(installProgress{Phase: "download-electron", Message: "Downloading the official Electron runtime…", Detail: formatBytes(downloaded), Percent: 59})
		}); err != nil {
			return installResult{}, err
		}
		report(installProgress{Phase: "verify-electron", Message: "Verifying Electron…", Percent: 65})
		if err := verifySHA256(runtimePath, expectedChecksum); err != nil {
			return installResult{}, err
		}
		report(installProgress{Phase: "extract-electron", Message: "Preparing the reusable Electron runtime…", Percent: 68})
		if err := os.MkdirAll(runtimeStagingDirectory, 0o755); err != nil {
			return installResult{}, err
		}
		if err := extractZip(ctx, runtimePath, runtimeStagingDirectory); err != nil {
			return installResult{}, err
		}
		if !runtimeReady(runtimeStagingDirectory, target) {
			return installResult{}, fmt.Errorf("the Electron runtime is incomplete for %s", target)
		}
		if err := activateInstallation(runtimeStagingDirectory, runtimeDirectory); err != nil {
			return installResult{}, err
		}
	} else {
		report(installProgress{Phase: "electron-reuse", Message: "Reusing Electron " + selected.ElectronVersion + "…", Percent: 70})
	}
	report(installProgress{Phase: "staging", Message: "Creating a safe staging area…", Percent: 73})
	if err := os.MkdirAll(stagingDirectory, 0o755); err != nil {
		return installResult{}, err
	}
	report(installProgress{Phase: "extract-shared", Message: "Extracting common package…", Percent: 78})
	if err := extractTarZst(ctx, sharedPath, stagingDirectory); err != nil {
		return installResult{}, err
	}
	report(installProgress{Phase: "extract-native", Message: "Adding native package…", Percent: 87})
	if err := extractTarZst(ctx, nativePath, stagingDirectory); err != nil {
		return installResult{}, err
	}
	if err := writeLauncher(stagingDirectory, target, selected.ElectronVersion); err != nil {
		return installResult{}, err
	}
	report(installProgress{Phase: "validate", Message: "Validating Orchard…", Percent: 94})
	if err := validateInstallation(stagingDirectory, version, target); err != nil {
		return installResult{}, err
	}
	report(installProgress{Phase: "activate", Message: "Activating the installation…", Detail: "The previous installation is protected until this completes.", Percent: 97})
	if err := activateInstallation(stagingDirectory, installDirectory); err != nil {
		return installResult{}, err
	}
	report(installProgress{Phase: "cleanup", Message: "Cleaning temporary files…", Percent: 99})
	return installResult{Version: version, Target: target, InstallPath: installDirectory}, nil
}

func (i *installer) downloadFile(ctx context.Context, sourceURL, destination string, expectedSize int64, progress func(int64)) error {
	partPath := destination + ".part"
	_ = os.Remove(partPath)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, sourceURL, nil)
	if err != nil {
		return err
	}
	response, err := i.client.Do(request)
	if err != nil {
		return fmt.Errorf("download failed: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("download failed with HTTP %d: %s", response.StatusCode, filepath.Base(request.URL.Path))
	}
	file, err := os.OpenFile(partPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return err
	}
	success := false
	defer func() {
		_ = file.Close()
		if !success {
			_ = os.Remove(partPath)
		}
	}()
	written, err := io.Copy(file, &progressReader{reader: response.Body, report: progress})
	if err != nil {
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	if expectedSize > 0 && written != expectedSize {
		return fmt.Errorf("downloaded %d bytes, but the manifest requires %d", written, expectedSize)
	}
	if err := os.Rename(partPath, destination); err != nil {
		return err
	}
	success = true
	return nil
}

type progressReader struct {
	reader io.Reader
	total  int64
	report func(int64)
}

func (reader *progressReader) Read(buffer []byte) (int, error) {
	count, err := reader.reader.Read(buffer)
	reader.total += int64(count)
	if count > 0 && reader.report != nil {
		reader.report(reader.total)
	}
	return count, err
}

func verifySHA256(filePath, expected string) error {
	file, err := os.Open(filePath)
	if err != nil {
		return err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return err
	}
	if hex.EncodeToString(hash.Sum(nil)) != strings.ToLower(expected) {
		return fmt.Errorf("checksum verification failed for %s", filepath.Base(filePath))
	}
	return nil
}

var windowsDrivePattern = regexp.MustCompile(`^[A-Za-z]:`)

func isSafeArchivePath(entry string) bool {
	normalized := strings.TrimPrefix(entry, "./")
	if normalized == "" || normalized == "." {
		return true
	}
	if strings.ContainsRune(normalized, 0) || strings.Contains(normalized, `\`) || strings.HasPrefix(normalized, "/") || windowsDrivePattern.MatchString(normalized) {
		return false
	}
	for _, part := range strings.Split(normalized, "/") {
		if part == ".." {
			return false
		}
	}
	return true
}

func extractTarZst(ctx context.Context, archivePath, destination string) error {
	archive, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer archive.Close()
	decoder, err := zstd.NewReader(archive)
	if err != nil {
		return fmt.Errorf("could not decompress package archive: %w", err)
	}
	defer decoder.Close()
	if err := os.MkdirAll(destination, 0o755); err != nil {
		return err
	}

	reader := tar.NewReader(&contextReader{ctx: ctx, reader: decoder})
	for {
		header, err := reader.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("could not read package archive: %w", err)
		}
		if !isSafeArchivePath(header.Name) {
			return fmt.Errorf("unsafe path in package archive: %s", header.Name)
		}
		if header.Typeflag == tar.TypeSymlink || header.Typeflag == tar.TypeLink {
			return fmt.Errorf("package archives may not contain symbolic or hard links")
		}
		relative := strings.TrimPrefix(header.Name, "./")
		if relative == "" || relative == "." {
			continue
		}
		outputPath := filepath.Join(destination, filepath.FromSlash(relative))
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(outputPath, 0o755); err != nil {
				return err
			}
		case tar.TypeReg, tar.TypeRegA:
			if err := os.MkdirAll(filepath.Dir(outputPath), 0o755); err != nil {
				return err
			}
			mode := os.FileMode(header.Mode & 0o777)
			file, err := os.OpenFile(outputPath, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
			if err != nil {
				return err
			}
			_, copyErr := io.Copy(file, reader)
			closeErr := file.Close()
			if copyErr != nil {
				return copyErr
			}
			if closeErr != nil {
				return closeErr
			}
		default:
			return fmt.Errorf("unsupported entry type in package archive: %s", header.Name)
		}
	}
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader *contextReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(buffer)
}

func validateInstallation(directory, version, target string) error {
	required := []string{
		"package.json", "dist/index.html", "dist/welcome.html", "electron/main/index.js", ".orchard-package.json",
		filepath.Join(".orchard-native", target+".json"),
		filepath.Join("native", "build", "Release", "orchard_audio_analysis.node"),
		filepath.Join("native-media", "build", "orchard-system-media-"+target+".node"),
		filepath.Join("native-audio-rust", "build", "orchard-audio-transition-"+target+".node"),
	}
	if target != "darwin-x64" {
		parts := strings.SplitN(target, "-", 2)
		required = append(required, filepath.Join("node_modules", "onnxruntime-node", "bin", "napi-v6", parts[0], parts[1], "onnxruntime_binding.node"))
	}
	if strings.HasPrefix(target, "win32-") {
		required = append(required, "orchard.cmd")
	} else {
		required = append(required, "orchard")
	}
	for _, relative := range required {
		if _, err := os.Stat(filepath.Join(directory, relative)); err != nil {
			return fmt.Errorf("the staged installation is incomplete: %s is missing", filepath.ToSlash(relative))
		}
	}
	metadataData, err := os.ReadFile(filepath.Join(directory, ".orchard-package.json"))
	if err != nil {
		return err
	}
	var metadata struct {
		SchemaVersion int    `json:"schemaVersion"`
		Version       string `json:"version"`
	}
	if json.Unmarshal(metadataData, &metadata) != nil || metadata.SchemaVersion != 1 || metadata.Version != version {
		return fmt.Errorf("the staged installation metadata does not match the selected release")
	}
	return nil
}

func activateInstallation(staging, destination string) error {
	backup := destination + ".backup-" + randomID()
	_, existingErr := os.Stat(destination)
	hadExisting := existingErr == nil
	if hadExisting {
		if err := os.Rename(destination, backup); err != nil {
			return err
		}
	}
	if err := os.Rename(staging, destination); err != nil {
		if hadExisting {
			if restoreErr := os.Rename(backup, destination); restoreErr != nil {
				return fmt.Errorf("activation failed and the previous installation could not be restored automatically; it remains at %s: %v", backup, restoreErr)
			}
		}
		return err
	}
	if hadExisting {
		_ = os.RemoveAll(backup)
	}
	return nil
}

func randomID() string {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		panic(err)
	}
	buffer[6] = buffer[6]&0x0f | 0x40
	buffer[8] = buffer[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", buffer[0:4], buffer[4:6], buffer[6:8], buffer[8:10], buffer[10:16])
}
