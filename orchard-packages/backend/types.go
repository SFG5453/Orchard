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
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
)

const packageBaseURL = "https://packages.sfg545.dev/"
const githubReleaseBaseURL = "https://github.com/sfg5453/orchard/releases/download/"
const githubReleasesAPIURL = "https://api.github.com/repos/sfg5453/orchard/releases?per_page=20"

func releaseBaseURL(candidate release) string {
	if candidate.Channel == "beta" {
		return githubReleaseBaseURL + "v" + candidate.Version + "/"
	}
	return packageBaseURL
}

var (
	sha256Pattern  = regexp.MustCompile(`^[a-f0-9]{64}$`)
	versionPattern = regexp.MustCompile(`^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$`)
	validTargets   = map[string]bool{
		"linux-x64": true, "linux-arm64": true,
		"win32-x64": true, "win32-arm64": true,
		"darwin-x64": true, "darwin-arm64": true,
	}
)

type packageAsset struct {
	URL    string `json:"url"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type release struct {
	Version         string                  `json:"version"`
	Channel         string                  `json:"channel"`
	Shared          packageAsset            `json:"shared"`
	Native          map[string]packageAsset `json:"native"`
	ElectronVersion string                  `json:"electronVersion"`
}

type packageManifest struct {
	SchemaVersion int       `json:"schemaVersion"`
	Releases      []release `json:"releases"`
}

func parseManifest(data []byte) (packageManifest, error) {
	var manifest packageManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return manifest, fmt.Errorf("the package manifest could not be read: %w", err)
	}
	if manifest.SchemaVersion != 1 || manifest.Releases == nil {
		return manifest, fmt.Errorf("the package manifest is invalid or uses an unsupported schema")
	}
	for i, candidate := range manifest.Releases {
		if !versionPattern.MatchString(candidate.Version) {
			return manifest, fmt.Errorf("release %d has an invalid version", i+1)
		}
		if candidate.Channel != "stable" && candidate.Channel != "beta" {
			return manifest, fmt.Errorf("release %s has an invalid channel", candidate.Version)
		}
		if err := validateAsset(candidate.Shared, fmt.Sprintf("release %s common package", candidate.Version)); err != nil {
			return manifest, err
		}
		if candidate.Native == nil {
			return manifest, fmt.Errorf("release %s has no native package map", candidate.Version)
		}
		if !versionPattern.MatchString(candidate.ElectronVersion) {
			return manifest, fmt.Errorf("release %s has an invalid Electron version", candidate.Version)
		}
		for target, asset := range candidate.Native {
			if !validTargets[target] {
				return manifest, fmt.Errorf("release %s contains unknown target %s", candidate.Version, target)
			}
			if err := validateAsset(asset, fmt.Sprintf("release %s native package %s", candidate.Version, target)); err != nil {
				return manifest, err
			}
		}
	}
	return manifest, nil
}

func validateAsset(asset packageAsset, label string) error {
	parsed, err := url.Parse(asset.URL)
	if err != nil || asset.URL == "" || parsed.IsAbs() || parsed.Host != "" || strings.HasPrefix(asset.URL, "/") || strings.Contains(asset.URL, `\`) {
		return fmt.Errorf("%s has an unsafe relative URL", label)
	}
	for _, part := range strings.Split(asset.URL, "/") {
		if part == ".." {
			return fmt.Errorf("%s has an unsafe relative URL", label)
		}
	}
	if asset.Size <= 0 {
		return fmt.Errorf("%s has an invalid byte size", label)
	}
	if !sha256Pattern.MatchString(strings.ToLower(asset.SHA256)) {
		return fmt.Errorf("%s has an invalid SHA-256 checksum", label)
	}
	return nil
}

func resolveAssetURL(asset packageAsset, baseURL string) (string, error) {
	base, err := url.Parse(baseURL)
	if err != nil {
		return "", err
	}
	reference, err := url.Parse(asset.URL)
	if err != nil {
		return "", err
	}
	resolved := base.ResolveReference(reference)
	if resolved.Scheme != base.Scheme || resolved.Host != base.Host || !strings.HasPrefix(resolved.Path, base.Path) {
		return "", fmt.Errorf("package URL escapes the configured package base: %s", asset.URL)
	}
	return resolved.String(), nil
}

func archiveName(asset packageAsset, baseURL string) (string, error) {
	resolved, err := resolveAssetURL(asset, baseURL)
	if err != nil {
		return "", err
	}
	parsed, err := url.Parse(resolved)
	if err != nil {
		return "", err
	}
	name := path.Base(parsed.Path)
	if !strings.HasSuffix(name, ".tar.zst") {
		return "", fmt.Errorf("unexpected package filename: %s", name)
	}
	return name, nil
}

func detectTarget() (string, error) {
	platform := runtime.GOOS
	if platform == "windows" {
		platform = "win32"
	}
	architecture := runtime.GOARCH
	if architecture == "amd64" {
		architecture = "x64"
	}
	if platform == "win32" {
		windowsArchitecture := strings.ToLower(os.Getenv("PROCESSOR_ARCHITEW6432") + os.Getenv("PROCESSOR_ARCHITECTURE"))
		if strings.Contains(windowsArchitecture, "arm64") {
			architecture = "arm64"
		}
	}
	target := platform + "-" + architecture
	if !validTargets[target] {
		return "", fmt.Errorf("unsupported platform: %s/%s", runtime.GOOS, runtime.GOARCH)
	}
	return target, nil
}

func installPaths(target, version, electronVersion string) (string, string, string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", "", "", err
	}
	platform := strings.SplitN(target, "-", 2)[0]
	major := strings.SplitN(version, ".", 2)[0] + ".0.0"
	switch platform {
	case "win32":
		configRoot := os.Getenv("APPDATA")
		if configRoot == "" {
			configRoot = filepath.Join(home, "AppData", "Roaming")
		}
		localData := os.Getenv("LOCALAPPDATA")
		if localData == "" {
			localData = filepath.Join(home, "AppData", "Local")
		}
		root := filepath.Join(configRoot, "orchard")
		return filepath.Join(root, "versions", major), filepath.Join(root, "runtimes", "electron", electronVersion, target), filepath.Join(localData, "Orchard Packages", "Cache"), nil
	case "darwin":
		root := filepath.Join(home, "Library", "Application Support", "orchard")
		return filepath.Join(root, "versions", major), filepath.Join(root, "runtimes", "electron", electronVersion, target), filepath.Join(home, "Library", "Caches", "Orchard Packages"), nil
	default:
		configRoot := os.Getenv("XDG_CONFIG_HOME")
		if configRoot == "" {
			configRoot = filepath.Join(home, ".config")
		}
		cacheRoot := os.Getenv("XDG_CACHE_HOME")
		if cacheRoot == "" {
			cacheRoot = filepath.Join(home, ".cache")
		}
		root := filepath.Join(configRoot, "orchard")
		return filepath.Join(root, "versions", major), filepath.Join(root, "runtimes", "electron", electronVersion, target), filepath.Join(cacheRoot, "orchard-packages"), nil
	}
}

func sortReleases(releases []release) {
	sort.SliceStable(releases, func(i, j int) bool {
		return compareVersions(releases[i].Version, releases[j].Version) > 0
	})
}

func compareVersions(left, right string) int {
	leftParts := strings.FieldsFunc(left, func(r rune) bool { return r == '.' || r == '-' })
	rightParts := strings.FieldsFunc(right, func(r rune) bool { return r == '.' || r == '-' })
	for i := 0; i < len(leftParts) || i < len(rightParts); i++ {
		if i >= len(leftParts) {
			return 1
		}
		if i >= len(rightParts) {
			return -1
		}
		leftNumber, leftErr := strconv.Atoi(leftParts[i])
		rightNumber, rightErr := strconv.Atoi(rightParts[i])
		var comparison int
		if leftErr == nil && rightErr == nil {
			comparison = leftNumber - rightNumber
		} else {
			comparison = strings.Compare(leftParts[i], rightParts[i])
		}
		if comparison != 0 {
			return comparison
		}
	}
	return 0
}
