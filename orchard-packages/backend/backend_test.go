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
	"archive/zip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/klauspost/compress/zstd"
)

func TestNeutralinoBootstrapAcceptsNumericPort(t *testing.T) {
	var bootstrap extensionBootstrap
	if err := json.Unmarshal([]byte(`{"nlPort":45479,"nlToken":"token","nlConnectToken":"connect","nlExtensionId":"extension"}`), &bootstrap); err != nil {
		t.Fatal(err)
	}
	if fmt.Sprint(bootstrap.Port) != "45479" {
		t.Fatalf("unexpected port: %v", bootstrap.Port)
	}
}

func TestNeutralinoStartupEventAcceptsStringData(t *testing.T) {
	var event extensionEvent
	if err := json.Unmarshal([]byte(`{"event":"extensionReady","data":"dev.sfg.orchard.packages.backend"}`), &event); err != nil {
		t.Fatal(err)
	}
	if event.Data != "dev.sfg.orchard.packages.backend" {
		t.Fatalf("unexpected startup data: %v", event.Data)
	}
}

func TestRequestIDsAreUUIDs(t *testing.T) {
	id := randomID()
	if !regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`).MatchString(id) {
		t.Fatalf("invalid UUID: %s", id)
	}
}

func TestManifestValidation(t *testing.T) {
	checksum := strings.Repeat("a", 64)
	data := `{"schemaVersion":1,"releases":[{"version":"5.0.0","channel":"stable","electronVersion":"43.4.1","shared":{"url":"orchard.tar.zst","size":10,"sha256":"` + checksum + `"},"native":{"linux-x64":{"url":"native.tar.zst","size":5,"sha256":"` + checksum + `"}}}]}`
	manifest, err := parseManifest([]byte(data))
	if err != nil || len(manifest.Releases) != 1 {
		t.Fatalf("valid manifest rejected: %v", err)
	}
	unsafe := strings.Replace(data, `"orchard.tar.zst"`, `"../orchard.tar.zst"`, 1)
	if _, err := parseManifest([]byte(unsafe)); err == nil {
		t.Fatal("unsafe asset URL was accepted")
	}
}

func TestInstallPathsUseMajorVersionSlotAndReusableElectron(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", "/tmp/orchard-config")
	t.Setenv("XDG_CACHE_HOME", "/tmp/orchard-cache")
	install, runtime, cache, err := installPaths("linux-x64", "5.8.2", "43.4.1")
	if err != nil {
		t.Fatal(err)
	}
	if install != "/tmp/orchard-config/orchard/versions/5.0.0" {
		t.Fatalf("unexpected install path: %s", install)
	}
	if runtime != "/tmp/orchard-config/orchard/runtimes/electron/43.4.1/linux-x64" {
		t.Fatalf("unexpected runtime path: %s", runtime)
	}
	if cache != "/tmp/orchard-cache/orchard-packages" {
		t.Fatalf("unexpected cache path: %s", cache)
	}
}

func TestFormatBytesUsesReadableUnits(t *testing.T) {
	tests := map[int64]string{
		512:       "512 B",
		1536:      "1.5 KB",
		98566144:  "94.0 MB",
		101034560: "96.4 MB",
	}
	for bytes, expected := range tests {
		if actual := formatBytes(bytes); actual != expected {
			t.Errorf("formatBytes(%d) = %q, want %q", bytes, actual, expected)
		}
	}
}

func TestDownloadUsesPartFileAndChecksSize(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(response, "orchard")
	}))
	defer server.Close()
	directory := t.TempDir()
	destination := filepath.Join(directory, "archive.tar.zst")
	service := newInstaller()
	if err := service.downloadFile(context.Background(), server.URL, destination, 7, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(destination + ".part"); !os.IsNotExist(err) {
		t.Fatal("download left a part file")
	}
	if err := service.downloadFile(context.Background(), server.URL, destination, 99, nil); err == nil {
		t.Fatal("download accepted the wrong size")
	}
}

func TestReleaseBaseURLUsesGitHubForBetaOnly(t *testing.T) {
	if actual := releaseBaseURL(release{Version: "5.0.0-beta.3", Channel: "beta"}); actual != "https://github.com/sfg5453/orchard/releases/download/v5.0.0-beta.3/" {
		t.Fatalf("beta base URL = %q", actual)
	}
	if actual := releaseBaseURL(release{Version: "5.0.0", Channel: "stable"}); actual != packageBaseURL {
		t.Fatalf("stable base URL = %q", actual)
	}
}

func TestLatestBetaManifestURLSkipsStableDraftAndUnsafeAssets(t *testing.T) {
	releases := []githubRelease{
		{Prerelease: false, Assets: []struct {
			Name               string `json:"name"`
			BrowserDownloadURL string `json:"browser_download_url"`
		}{{Name: "manifest.json", BrowserDownloadURL: "https://github.com/sfg5453/orchard/releases/download/v5.0.0/manifest.json"}}},
		{Prerelease: true, Assets: []struct {
			Name               string `json:"name"`
			BrowserDownloadURL string `json:"browser_download_url"`
		}{{Name: "manifest.json", BrowserDownloadURL: "https://example.com/manifest.json"}}},
		{Prerelease: true, Assets: []struct {
			Name               string `json:"name"`
			BrowserDownloadURL string `json:"browser_download_url"`
		}{{Name: "manifest.json", BrowserDownloadURL: "https://github.com/sfg5453/orchard/releases/download/v5.0.0-beta.3/manifest.json"}}},
	}
	want := "https://github.com/sfg5453/orchard/releases/download/v5.0.0-beta.3/manifest.json"
	if actual := latestBetaManifestURL(releases); actual != want {
		t.Fatalf("beta manifest URL = %q, want %q", actual, want)
	}
}

func TestExtractTarZstWithoutExternalTools(t *testing.T) {
	directory := t.TempDir()
	archive := filepath.Join(directory, "sample.tar.zst")
	writeTestArchive(t, archive, []*tar.Header{{Name: "dist/index.html", Mode: 0o644, Size: 7, Typeflag: tar.TypeReg}}, []string{"orchard"})
	output := filepath.Join(directory, "output")
	if err := extractTarZst(context.Background(), archive, output); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(output, "dist", "index.html"))
	if err != nil || string(data) != "orchard" {
		t.Fatalf("unexpected extracted file: %q, %v", data, err)
	}
}

func TestExtractTarZstRejectsUnsafeEntries(t *testing.T) {
	for _, header := range []*tar.Header{
		{Name: "../outside", Mode: 0o644, Size: 1, Typeflag: tar.TypeReg},
		{Name: "link", Linkname: "../outside", Typeflag: tar.TypeSymlink},
	} {
		t.Run(header.Name, func(t *testing.T) {
			directory := t.TempDir()
			archive := filepath.Join(directory, "unsafe.tar.zst")
			contents := []string{""}
			if header.Typeflag == tar.TypeReg {
				contents[0] = "x"
			}
			writeTestArchive(t, archive, []*tar.Header{header}, contents)
			if err := extractTarZst(context.Background(), archive, filepath.Join(directory, "output")); err == nil {
				t.Fatal("unsafe archive was accepted")
			}
		})
	}
}

func TestExtractElectronZipPreservesExecutable(t *testing.T) {
	directory := t.TempDir()
	archivePath := filepath.Join(directory, "electron.zip")
	header := &zip.FileHeader{Name: "electron", Method: zip.Deflate}
	header.SetMode(0o755)
	writeTestZip(t, archivePath, []*zip.FileHeader{header}, []string{"electron"})

	output := filepath.Join(directory, "runtime")
	if err := extractZip(context.Background(), archivePath, output); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(filepath.Join(output, "electron"))
	if err != nil || info.Mode().Perm() != 0o755 {
		t.Fatalf("Electron executable mode was not preserved: %v, %v", info, err)
	}
}

func TestWindowsLauncherTerminatesAppPathAfterDot(t *testing.T) {
	directory := t.TempDir()
	if err := writeLauncher(directory, "win32-x64", "43.4.1"); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(directory, "orchard.cmd"))
	if err != nil {
		t.Fatal(err)
	}
	expected := "@echo off\r\n" +
		"\"%~dp0..\\..\\runtimes\\electron\\43.4.1\\win32-x64\\electron.exe\" \"%~dp0.\" %*\r\n"
	if string(data) != expected {
		t.Fatalf("unexpected Windows launcher:\n%s", data)
	}
}

func TestValidateInstallationRequiresWelcomeEntryPoint(t *testing.T) {
	directory := t.TempDir()
	if err := os.MkdirAll(filepath.Join(directory, "dist"), 0o755); err != nil {
		t.Fatal(err)
	}
	for _, relativePath := range []string{"package.json", filepath.Join("dist", "index.html")} {
		if err := os.WriteFile(filepath.Join(directory, relativePath), []byte("{}"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	err := validateInstallation(directory, "5.0.0-beta.6", "win32-x64")
	if err == nil || !strings.Contains(err.Error(), "dist/welcome.html is missing") {
		t.Fatalf("expected missing welcome entry point error, got %v", err)
	}
}

func TestValidateInstallationAcceptsLegacyBeta6WindowsLayout(t *testing.T) {
	directory := t.TempDir()
	files := []string{
		"package.json",
		"dist/index.html",
		"electron/main/index.js",
		".orchard-package.json",
		".orchard-native/win32-x64.json",
		"native-media/build/orchard-system-media-win32-x64.node",
		"native-audio-rust/build/orchard-audio-transition-win32-x64.node",
		"native/build/Release/orchard_audio_analysis.node",
		"node_modules/onnxruntime-node/bin/napi-v6/win32/x64/onnxruntime_binding.node",
		"orchard.cmd",
	}
	for _, relative := range files {
		filePath := filepath.Join(directory, filepath.FromSlash(relative))
		if err := os.MkdirAll(filepath.Dir(filePath), 0o755); err != nil {
			t.Fatal(err)
		}
		contents := []byte("fixture")
		if relative == ".orchard-package.json" {
			contents = []byte(`{"schemaVersion":1,"version":"5.0.0-beta.6"}`)
		}
		if err := os.WriteFile(filePath, contents, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := validateInstallation(directory, "5.0.0-beta.6", "win32-x64"); err != nil {
		t.Fatalf("legacy Windows audio addon was rejected: %v", err)
	}
}

func TestExtractElectronZipPreservesFrameworkSymlinks(t *testing.T) {
	directory := t.TempDir()
	archivePath := filepath.Join(directory, "electron.zip")
	framework := "Electron.app/Contents/Frameworks/Electron Framework.framework"
	fileHeader := &zip.FileHeader{Name: framework + "/Versions/A/Electron Framework", Method: zip.Deflate}
	fileHeader.SetMode(0o755)
	currentHeader := &zip.FileHeader{Name: framework + "/Versions/Current", Method: zip.Store}
	currentHeader.SetMode(os.ModeSymlink | 0o777)
	frameworkHeader := &zip.FileHeader{Name: framework + "/Electron Framework", Method: zip.Store}
	frameworkHeader.SetMode(os.ModeSymlink | 0o777)
	writeTestZip(t, archivePath, []*zip.FileHeader{fileHeader, currentHeader, frameworkHeader}, []string{"framework", "A", "Versions/Current/Electron Framework"})

	output := filepath.Join(directory, "runtime")
	if err := extractZip(context.Background(), archivePath, output); err != nil {
		t.Fatal(err)
	}
	currentPath := filepath.Join(output, filepath.FromSlash(framework), "Versions", "Current")
	info, err := os.Lstat(currentPath)
	if err != nil || info.Mode()&os.ModeSymlink == 0 {
		t.Fatalf("framework version link was not preserved: %v, %v", info, err)
	}
	data, err := os.ReadFile(filepath.Join(output, filepath.FromSlash(framework), "Electron Framework"))
	if err != nil || string(data) != "framework" {
		t.Fatalf("framework link does not resolve to its binary: %q, %v", data, err)
	}
}

func TestExtractElectronZipRejectsUnsafeSymlinks(t *testing.T) {
	tests := []struct {
		name     string
		entries  []*zip.FileHeader
		contents []string
	}{
		{
			name:     "target outside extraction root",
			entries:  []*zip.FileHeader{zipSymlinkHeader("Electron.app/escape")},
			contents: []string{"../../outside"},
		},
		{
			name:     "entry beneath a symlink",
			entries:  []*zip.FileHeader{zipSymlinkHeader("Electron.app/Current"), zipRegularHeader("Electron.app/Current/file")},
			contents: []string{"Versions/A", "malicious"},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			directory := t.TempDir()
			archivePath := filepath.Join(directory, "electron.zip")
			writeTestZip(t, archivePath, test.entries, test.contents)
			if err := extractZip(context.Background(), archivePath, filepath.Join(directory, "runtime")); err == nil {
				t.Fatal("unsafe Electron archive was accepted")
			}
		})
	}
}

func zipSymlinkHeader(name string) *zip.FileHeader {
	header := &zip.FileHeader{Name: name, Method: zip.Store}
	header.SetMode(os.ModeSymlink | 0o777)
	return header
}

func zipRegularHeader(name string) *zip.FileHeader {
	header := &zip.FileHeader{Name: name, Method: zip.Deflate}
	header.SetMode(0o644)
	return header
}

func writeTestZip(t *testing.T, output string, headers []*zip.FileHeader, contents []string) {
	t.Helper()
	file, err := os.Create(output)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	for index, header := range headers {
		entry, err := archive.CreateHeader(header)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := io.WriteString(entry, contents[index]); err != nil {
			t.Fatal(err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
}

func writeTestArchive(t *testing.T, output string, headers []*tar.Header, contents []string) {
	t.Helper()
	file, err := os.Create(output)
	if err != nil {
		t.Fatal(err)
	}
	encoder, err := zstd.NewWriter(file)
	if err != nil {
		t.Fatal(err)
	}
	archive := tar.NewWriter(encoder)
	for index, header := range headers {
		if err := archive.WriteHeader(header); err != nil {
			t.Fatal(err)
		}
		if contents[index] != "" {
			if _, err := io.WriteString(archive, contents[index]); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	encoder.Close()
	file.Close()
}
