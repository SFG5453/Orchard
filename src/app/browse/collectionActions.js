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

import { ref } from 'vue';

function stopMenuEvent(event) {
  event?.preventDefault?.();
  event?.stopPropagation?.();
}

export function installCollectionActions(ctx) {
  ctx.collectionActionMenu = ref({
    open: false,
    x: 0,
    y: 0,
    item: null,
    source: []
  });

  ctx.closeCollectionActionMenu = function closeCollectionActionMenu() {
    ctx.collectionActionMenu.value = { ...ctx.collectionActionMenu.value, open: false };
  };

  ctx.openCollectionActionMenu = function openCollectionActionMenu(item, event, source = []) {
    stopMenuEvent(event);
    if (!ctx.resolveBrowseKind(item)) return;

    ctx.collectionActionMenu.value = {
      open: true,
      x: Number(event?.clientX) || window.innerWidth / 2,
      y: Number(event?.clientY) || window.innerHeight / 2,
      item,
      source
    };
  };

  ctx.openHomeMediaContextMenu = function openHomeMediaContextMenu(item, event, source = []) {
    if (ctx.isPlayableTrack(item)) {
      ctx.openSongActionMenu(item, event);
      return;
    }

    ctx.openCollectionActionMenu(item, event, source);
  };

  ctx.onHomeMediaKeydown = function onHomeMediaKeydown(event, item, source = []) {
    if (event.key !== 'ContextMenu' && !(event.shiftKey && event.key === 'F10')) return;
    const bounds = event.currentTarget?.getBoundingClientRect?.();
    ctx.openHomeMediaContextMenu(item, {
      clientX: bounds ? bounds.left + Math.min(bounds.width, 220) : window.innerWidth / 2,
      clientY: bounds ? bounds.top + Math.min(bounds.height, 36) : window.innerHeight / 2,
      preventDefault: () => event.preventDefault(),
      stopPropagation: () => event.stopPropagation()
    }, source);
  };

  ctx.runCollectionAction = async function runCollectionAction(action) {
    const { item, source } = ctx.collectionActionMenu.value;
    ctx.closeCollectionActionMenu();
    const kind = ctx.resolveBrowseKind(item);
    if (!kind || !item) return;

    if (action === 'share') {
      ctx.shareCollectionLink?.(item, null, null, source);
      return;
    }

    await ctx.openCollection(kind, item);
    if (action === 'open' || ctx.activeView.value !== 'browse') return;
    if (action === 'play') ctx.playCollection(ctx.browseDetail.value);
    if (action === 'shuffle') ctx.playCollection(ctx.browseDetail.value, { shuffle: true });
    if (action === 'download') void ctx.downloadCollection(ctx.browseDetail.value);
  };
}
