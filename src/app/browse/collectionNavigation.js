import { nextTick } from 'vue';

function itemCountNumber(value = '') {
  const match = String(value).match(/([\d,]+)\s+(?:songs?|tracks?|videos?)\b/i);
  return match ? Number(match[1].replace(/,/g, '')) || 0 : 0;
}

export function collectionItemCount(item = {}, data = {}) {
  const libraryItemCount = Math.max(
    itemCountNumber(item.itemCount),
    itemCountNumber(item.subtitle)
  );
  return libraryItemCount || Number(data.totalTrackCount) || itemCountNumber(data.itemCount);
}

export async function openCollectionWithLoading(ctx, kind, item) {
  const browsePayload = item?.browsePayload ? { ...item.browsePayload } : {};
  const browseId = browsePayload.browseId || ctx.itemBrowseId(item);
  if (!browseId || !ctx.socket.value?.connected) return;

  const previousEntry = ctx.createNavigationEntry();
  const previousView = ctx.activeView.value;
  const previousDetail = ctx.browseDetail.value;
  const previousSectionMore = ctx.sectionMoreDetail.value;
  const origin = previousView === 'browse' ? ctx.browseOrigin.value : previousView;

  ctx.resetBrowseTrackPaging();
  ctx.browseLoading.value = true;
  ctx.errorMessage.value = '';
  ctx.warningMessage.value = '';
  ctx.browseDetail.value = null;
  ctx.sectionMoreDetail.value = null;
  ctx.activeView.value = 'browse';
  await nextTick();

  try {
    const data = await ctx.emitWithReply(`music:${kind}`, { ...browsePayload, browseId });
    const totalTrackCount = collectionItemCount(item, data);
    ctx.pushNavigationEntry(previousEntry);
    ctx.browseOrigin.value = origin;
    ctx.browseDetail.value = {
      ...data,
      title: data.title || item.title,
      thumbnail: data.thumbnail || item.thumbnail || null,
      artist: data.artist || item.artist || item.artists?.join(', ') || item.subtitle || '',
      itemCount: totalTrackCount ? `${totalTrackCount.toLocaleString('en-US')} tracks` : (item.itemCount || data.itemCount),
      totalTrackCount,
      kind: data.kind || kind
    };
    await nextTick();
    void ctx.prefetchBrowseTrackPages();
    ctx.writeLastPageEntry();
  } catch (error) {
    ctx.activeView.value = previousView;
    ctx.browseDetail.value = previousDetail;
    ctx.sectionMoreDetail.value = previousSectionMore;
    ctx.errorMessage.value = error.message;
  } finally {
    ctx.browseLoading.value = false;
  }
}
