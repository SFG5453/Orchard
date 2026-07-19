<script>
export default {
  name: 'LiveShowsDialog',
  props: { app: { type: Object, required: true } },
  setup(props) {
    return props.app;
  }
};
</script>

<template>
  <q-dialog v-model="liveShowsDialogOpen" aria-label="Find live shows">
    <q-card class="live-shows-dialog">
      <header class="live-shows-dialog__header">
        <div>
          <strong>Find live shows</strong>
          <span>Search Ticketmaster for music events within 75 miles.</span>
        </div>
        <q-btn flat round dense icon="close" aria-label="Close live shows" @click="closeLiveShows" />
      </header>

      <form class="live-shows-dialog__form" @submit.prevent="findLiveShowsByLocation">
        <label for="live-shows-location">City, state, or ZIP code</label>
        <div class="live-shows-dialog__location-row">
          <input
            id="live-shows-location"
            v-model="liveShowsLocation"
            type="text"
            maxlength="100"
            autocomplete="postal-code"
            placeholder="Chicago, IL or 60614"
            :disabled="liveShowsLoading"
          />
          <button type="submit" :disabled="liveShowsLoading || liveShowsLocation.trim().length < 2">
            <q-spinner v-if="liveShowsLoading && !liveShowsUsingCurrentLocation" size="16px" />
            <span v-else>Search</span>
          </button>
        </div>
      </form>

      <div class="live-shows-dialog__divider"><span>or</span></div>

      <button
        type="button"
        class="live-shows-dialog__current"
        :disabled="liveShowsLoading"
        @click="findLiveShowsNearMe"
      >
        <q-spinner v-if="liveShowsLoading && liveShowsUsingCurrentLocation" size="17px" />
        <q-icon v-else name="my_location" />
        <span>Use current location</span>
      </button>

      <div v-if="liveShowsError" class="live-shows-dialog__error" role="alert">
        <q-icon name="warning" />
        <span>{{ liveShowsError }}</span>
      </div>

      <p class="live-shows-dialog__privacy">
        Your location is sent only to Orchard's concert service to perform this search.
      </p>
    </q-card>
  </q-dialog>
</template>
