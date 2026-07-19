<script>
export default {
  name: 'ExplicitBadge',
  props: {
    explicit: { type: Boolean, default: false }
  }
};
</script>

<template>
  <span v-if="explicit" class="explicit-badge" aria-label="Explicit" title="Explicit">E</span>
</template>
