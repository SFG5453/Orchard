<script>
import { computed, nextTick, ref, watch } from 'vue';

export default {
  name: 'CollectionActionMenu',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const menuRef = ref(null);
    const menuStyle = computed(() => {
      const menu = props.app.collectionActionMenu.value;
      const width = 248;
      const height = 230;
      const gutter = 12;
      return {
        left: `${Math.max(gutter, Math.min(menu.x, window.innerWidth - width - gutter))}px`,
        top: `${Math.max(40, Math.min(menu.y, window.innerHeight - height - gutter))}px`
      };
    });

    watch(() => props.app.collectionActionMenu.value.open, async (open) => {
      if (!open) return;
      await nextTick();
      menuRef.value?.querySelector('button')?.focus();
    });

    function onKeydown(event) {
      if (event.key === 'Escape') {
        props.app.closeCollectionActionMenu();
        return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      const buttons = [...menuRef.value.querySelectorAll('button:not(:disabled)')];
      if (!buttons.length) return;
      event.preventDefault();
      const current = buttons.indexOf(document.activeElement);
      let next = event.key === 'Home' ? 0 : event.key === 'End' ? buttons.length - 1 : current;
      if (event.key === 'ArrowDown') next = (current + 1) % buttons.length;
      if (event.key === 'ArrowUp') next = (current - 1 + buttons.length) % buttons.length;
      buttons[next]?.focus();
    }

    return { ...props.app, menuRef, menuStyle, onKeydown };
  }
};
</script>

<template>
  <Teleport to="body">
    <div
      v-if="collectionActionMenu.open"
      class="collection-action-layer"
      @mousedown.self="closeCollectionActionMenu"
      @contextmenu.prevent.self="closeCollectionActionMenu"
    >
      <section
        ref="menuRef"
        class="collection-action-menu"
        :style="menuStyle"
        role="menu"
        :aria-label="`Actions for ${collectionActionMenu.item?.title || 'collection'}`"
        @keydown="onKeydown"
        @contextmenu.prevent
      >
        <div class="collection-action-menu__title">
          <strong>{{ collectionActionMenu.item?.title }}</strong>
          <span>{{ itemTypeLabel(collectionActionMenu.item) }}</span>
        </div>
        <div class="collection-action-menu__items">
          <button type="button" role="menuitem" @click="runCollectionAction('open')">
            <q-icon name="open_in_new" /><span>Open</span>
          </button>
          <button type="button" role="menuitem" @click="runCollectionAction('play')">
            <q-icon name="play_arrow" /><span>Play</span>
          </button>
          <button type="button" role="menuitem" @click="runCollectionAction('shuffle')">
            <q-icon name="shuffle" /><span>Shuffle</span>
          </button>
          <button type="button" role="menuitem" @click="runCollectionAction('share')">
            <q-icon name="ios_share" /><span>Share</span>
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>
