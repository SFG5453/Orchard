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
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"sync"

	"github.com/gorilla/websocket"
)

type extensionBootstrap struct {
	Port         any    `json:"nlPort"`
	Token        string `json:"nlToken"`
	ConnectToken string `json:"nlConnectToken"`
	ExtensionID  string `json:"nlExtensionId"`
}

type extensionEvent struct {
	ID    string `json:"id"`
	Event string `json:"event"`
	Data  any    `json:"data"`
}

type extension struct {
	connection *websocket.Conn
	token      string
	writeMu    sync.Mutex
	installMu  sync.Mutex
	installing bool
}

func main() {
	if err := runExtension(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func runExtension() error {
	var bootstrap extensionBootstrap
	if err := json.NewDecoder(os.Stdin).Decode(&bootstrap); err != nil {
		return fmt.Errorf("Neutralino extension bootstrap data is invalid: %w", err)
	}
	port := fmt.Sprint(bootstrap.Port)
	if bootstrap.Port == nil || port == "" || bootstrap.Token == "" || bootstrap.ConnectToken == "" || bootstrap.ExtensionID == "" {
		return fmt.Errorf("Neutralino extension bootstrap data is incomplete")
	}
	query := url.Values{
		"extensionId":  {bootstrap.ExtensionID},
		"connectToken": {bootstrap.ConnectToken},
	}
	connection, _, err := websocket.DefaultDialer.Dial("ws://127.0.0.1:"+port+"?"+query.Encode(), nil)
	if err != nil {
		return fmt.Errorf("could not connect the package backend to Neutralino: %w", err)
	}
	defer connection.Close()
	service := &extension{connection: connection, token: bootstrap.Token}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var installs sync.WaitGroup

	for {
		var event extensionEvent
		if err := connection.ReadJSON(&event); err != nil {
			cancel()
			installs.Wait()
			if websocket.IsCloseError(err, websocket.CloseNormalClosure, websocket.CloseGoingAway) {
				return nil
			}
			return fmt.Errorf("Neutralino extension connection closed: %w", err)
		}
		if event.Event == "" {
			if data, ok := event.Data.(map[string]any); ok && data["error"] != nil {
				return fmt.Errorf("Neutralino rejected backend request %s: %v", event.ID, data["error"])
			}
			continue
		}
		data, ok := event.Data.(map[string]any)
		if !ok {
			continue
		}
		requestID, _ := data["requestId"].(string)
		if requestID == "" {
			continue
		}
		switch event.Event {
		case "packages.requestManifest":
			service.sendManifest(ctx, requestID)
		case "packages.install":
			version, _ := data["version"].(string)
			if !service.beginInstall() {
				_ = service.broadcast("packages.error", map[string]any{"requestId": requestID, "message": "Another Orchard installation is already running."})
				continue
			}
			installs.Add(1)
			go func() {
				defer installs.Done()
				defer service.finishInstall()
				service.install(ctx, requestID, version)
			}()
		case "packages.open":
			version, _ := data["version"].(string)
			service.open(ctx, requestID, version)
		}
	}
}

func (extension *extension) beginInstall() bool {
	extension.installMu.Lock()
	defer extension.installMu.Unlock()
	if extension.installing {
		return false
	}
	extension.installing = true
	return true
}

func (extension *extension) finishInstall() {
	extension.installMu.Lock()
	extension.installing = false
	extension.installMu.Unlock()
}

func (extension *extension) broadcast(event string, data map[string]any) error {
	extension.writeMu.Lock()
	defer extension.writeMu.Unlock()
	return extension.connection.WriteJSON(map[string]any{
		"id": randomID(), "method": "app.broadcast", "accessToken": extension.token,
		"data": map[string]any{"event": event, "data": data},
	})
}

func (extension *extension) sendManifest(ctx context.Context, requestID string) {
	target, err := detectTarget()
	if err != nil {
		extension.sendError(requestID, err)
		return
	}
	manifest, err := newInstaller().fetchAvailableManifest(ctx)
	if err != nil {
		extension.sendError(requestID, err)
		return
	}
	sortReleases(manifest.Releases)
	releases := make([]map[string]any, 0, len(manifest.Releases))
	for _, candidate := range manifest.Releases {
		native, ok := candidate.Native[target]
		if !ok {
			continue
		}
		installed := false
		installDirectory, runtimeDirectory, _, pathErr := installPaths(target, candidate.Version, candidate.ElectronVersion)
		if pathErr == nil && runtimeReady(runtimeDirectory, target) && validateInstallation(installDirectory, candidate.Version, target) == nil {
			installed = true
		}
		releases = append(releases, map[string]any{
			"version": candidate.Version, "channel": candidate.Channel,
			"sharedSize": candidate.Shared.Size, "nativeSize": native.Size, "installed": installed,
		})
	}
	_ = extension.broadcast("packages.manifest", map[string]any{"requestId": requestID, "target": target, "releases": releases})
}

func (extension *extension) open(ctx context.Context, requestID, version string) {
	if err := newInstaller().openRelease(ctx, version); err != nil {
		extension.sendError(requestID, err)
		return
	}
	_ = extension.broadcast("packages.opened", map[string]any{"requestId": requestID, "version": version})
}

func (extension *extension) install(ctx context.Context, requestID, version string) {
	result, err := newInstaller().installRelease(ctx, version, func(progress installProgress) {
		data := map[string]any{"requestId": requestID, "phase": progress.Phase, "message": progress.Message, "percent": progress.Percent}
		if progress.Detail != "" {
			data["detail"] = progress.Detail
		}
		_ = extension.broadcast("packages.progress", data)
	})
	if err != nil {
		extension.sendError(requestID, err)
		return
	}
	_ = extension.broadcast("packages.success", map[string]any{
		"requestId": requestID, "version": result.Version, "target": result.Target, "installPath": result.InstallPath,
	})
}

func (extension *extension) sendError(requestID string, err error) {
	_ = extension.broadcast("packages.error", map[string]any{"requestId": requestID, "message": err.Error()})
}
