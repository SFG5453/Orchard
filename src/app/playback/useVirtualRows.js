import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';

const OVERSCAN = 8;

/**
 * Builds the table of row tops. `offsets[i]` is the top of row i and the last
 * entry is the total height, so a row's own height is the gap to the next entry
 * minus whatever heading sits above it.
 */
export function rowOffsets(list = [], rowHeight = 0, headerFor = () => 0) {
  const table = new Array(list.length + 1);
  let running = 0;
  for (let index = 0; index < list.length; index += 1) {
    running += headerFor(list[index]);
    table[index] = running;
    running += rowHeight;
  }
  table[list.length] = running;
  return table;
}

/**
 * The last row whose top is at or above `pixel`. Offsets ascend, so this is a
 * binary search rather than a scan -- the difference matters at five thousand
 * rows being re-measured on every scroll frame.
 */
export function rowIndexAt(table = [], pixel = 0) {
  let low = 0;
  let high = Math.max(0, table.length - 2);
  while (low < high) {
    const middle = (low + high + 1) >> 1;
    if (table[middle] <= pixel) low = middle;
    else high = middle - 1;
  }
  return low;
}

/**
 * Windows a long list down to the rows actually on screen.
 *
 * Rows are not all the same height -- the continuous queue puts a section
 * heading above the first entry of each section -- so offsets come from a
 * prefix sum rather than `index * ROW_HEIGHT`. The sum is rebuilt only when the
 * list itself changes, which on a shuffled playlist is once per continuation
 * page rather than once per scroll frame.
 *
 * @param rows        reactive array of row descriptors
 * @param rowHeight   height of an ordinary row, in pixels
 * @param headerFor   optional (row) => extra pixels above this row, e.g. a heading
 * @param contentRef   template ref for the element holding the rows
 * @param resolveRoot () => the scrolling ancestor; defaults to the nearest `.page`
 */
export function useVirtualRows({
  rows,
  rowHeight,
  headerFor = () => 0,
  contentRef,
  resolveRoot = (element) => element?.closest('.page') || null
}) {
  const range = ref({ start: 0, end: 0 });
  // `rowHeight` is the caller's expectation; this is what the stylesheet
  // actually produced. Queue rows change height at the responsive breakpoints,
  // and offsets computed from a stale number put every row in the wrong place.
  const measuredHeight = ref(rowHeight);
  let scrollRoot = null;
  let frame = 0;

  const offsets = computed(() => rowOffsets(rows.value || [], measuredHeight.value, headerFor));
  const totalHeight = computed(() => offsets.value[offsets.value.length - 1] || 0);

  const visibleRows = computed(() => {
    const list = rows.value || [];
    const start = Math.max(0, range.value.start);
    const end = Math.min(range.value.end, list.length);
    const cells = [];
    for (let index = start; index < end; index += 1) {
      cells.push({ index, row: list[index], top: offsets.value[index] });
    }
    return cells;
  });

  function updateRange() {
    const list = rows.value || [];
    const listBounds = contentRef.value?.getBoundingClientRect();
    const rootBounds = scrollRoot?.getBoundingClientRect();
    if (!listBounds || !rootBounds) {
      // Before the list is measurable, render a screenful so the panel is never
      // blank on first paint.
      range.value = { start: 0, end: Math.min(list.length, OVERSCAN * 4) };
      return;
    }

    const top = rootBounds.top - listBounds.top;
    const first = rowIndexAt(offsets.value, top);
    const last = rowIndexAt(offsets.value, top + rootBounds.height);
    range.value = {
      start: Math.max(0, first - OVERSCAN),
      end: Math.min(list.length, last + 1 + OVERSCAN)
    };
  }

  function flush() {
    frame = 0;
    updateRange();
  }

  function onScroll() {
    if (frame) return;
    frame = requestAnimationFrame(flush);
  }

  function scrollToIndex(index) {
    const list = rows.value || [];
    if (!scrollRoot || !contentRef.value || !list.length) return;
    const target = Math.max(0, Math.min(Number(index) || 0, list.length - 1));
    const listBounds = contentRef.value.getBoundingClientRect();
    const rootBounds = scrollRoot.getBoundingClientRect();
    const listTop = scrollRoot.scrollTop + listBounds.top - rootBounds.top;
    const centered = Math.max(0, (scrollRoot.clientHeight - measuredHeight.value) / 2);
    scrollRoot.scrollTo({ top: Math.max(0, listTop + offsets.value[target] - centered), behavior: 'smooth' });
  }

  // Reads back what the stylesheet gave the first row. Rows are placed by
  // transform, so `measureRow` has to return an element whose height is its
  // natural one -- the caller marks that element rather than this guessing.
  function remeasure() {
    const row = contentRef.value?.querySelector('[data-virtual-row]');
    const height = row?.getBoundingClientRect().height || 0;
    if (height > 0 && Math.abs(height - measuredHeight.value) >= 0.5) {
      measuredHeight.value = height;
      return true;
    }
    return false;
  }

  function onResize() {
    remeasure();
    updateRange();
  }

  onMounted(async () => {
    // The scroller has to be laid out before its bounds mean anything.
    await nextTick();
    scrollRoot = resolveRoot(contentRef.value);
    updateRange();
    // One more pass once rows exist, in case the stylesheet disagrees with the
    // constant the caller passed.
    await nextTick();
    if (remeasure()) updateRange();
    scrollRoot?.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onResize);
  });

  onBeforeUnmount(() => {
    scrollRoot?.removeEventListener('scroll', onScroll);
    window.removeEventListener('resize', onResize);
    if (frame) cancelAnimationFrame(frame);
  });

  watch(() => (rows.value || []).length, updateRange);

  return { range, rowHeight: measuredHeight, scrollToIndex, totalHeight, updateRange, visibleRows };
}
