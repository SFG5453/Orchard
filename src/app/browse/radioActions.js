import { ref } from 'vue';

const concertsEndpoint = 'https://concerts.sfg545.dev/events';
const liveShowsLocationKey = 'orchard:live-shows-location';

function storedLocation() {
  try {
    return window.localStorage.getItem(liveShowsLocationKey) || '';
  } catch {
    return '';
  }
}

function eventDateLabel(event) {
  if (!event?.date) return 'Date to be announced';
  const date = new Date(`${event.date}T${event.time || '12:00:00'}`);
  if (Number.isNaN(date.getTime())) return event.date;

  return new Intl.DateTimeFormat(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    year: date.getFullYear() === new Date().getFullYear() ? undefined : 'numeric'
  }).format(date);
}

function orchardEvent(event) {
  const place = [event.city, event.state].filter(Boolean).join(', ');
  return {
    ...event,
    type: 'event',
    subtitle: [eventDateLabel(event), event.venue].filter(Boolean).join(' · '),
    itemCount: place
  };
}

export function installRadioActions(ctx) {
  ctx.liveShowsDialogOpen = ref(false);
  ctx.liveShowsLocation = ref(storedLocation());
  ctx.liveShowsLoading = ref(false);
  ctx.liveShowsUsingCurrentLocation = ref(false);
  ctx.liveShowsError = ref('');

  ctx.openPersonalizedRadio = async function openPersonalizedRadio() {
    if (!ctx.authState.value.signedIn) {
      ctx.selectView('home');
      return;
    }

    if (!ctx.socket.value?.connected) return;

    ctx.errorMessage.value = '';
    ctx.warningMessage.value = '';

    try {
      const radio = await ctx.emitWithReply('music:radio');
      await ctx.openCollection('playlist', radio);
    } catch (error) {
      ctx.errorMessage.value = error.message || 'Could not load your personalized radio.';
    }
  };

  ctx.openLiveShows = function openLiveShows() {
    ctx.liveShowsError.value = '';
    ctx.liveShowsDialogOpen.value = true;
  };

  ctx.closeLiveShows = function closeLiveShows() {
    if (!ctx.liveShowsLoading.value) ctx.liveShowsDialogOpen.value = false;
  };

  ctx.loadLiveShows = async function loadLiveShows(parameters) {
    ctx.liveShowsLoading.value = true;
    ctx.liveShowsError.value = '';
    ctx.query.value = '';
    ctx.selectedFilter.value = 'all';

    try {
      const url = new URL(concertsEndpoint);
      for (const [key, value] of Object.entries(parameters)) url.searchParams.set(key, String(value));
      const response = await fetch(url, { headers: { accept: 'application/json' } });
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || `Concert search failed with HTTP ${response.status}.`);

      const events = (data.events || []).map(orchardEvent);
      ctx.searchResult.value = {
        source: 'ticketmaster',
        location: data.location || parameters.location || 'Current location',
        sections: events.length ? [{ key: 'events', title: 'Upcoming concerts', items: events }] : []
      };
      ctx.navigateToView('search');
      ctx.liveShowsDialogOpen.value = false;
    } catch (error) {
      ctx.liveShowsError.value = error.message || 'Could not find live shows.';
    } finally {
      ctx.liveShowsLoading.value = false;
      ctx.liveShowsUsingCurrentLocation.value = false;
    }
  };

  ctx.findLiveShowsByLocation = async function findLiveShowsByLocation() {
    const location = ctx.liveShowsLocation.value.trim();
    if (location.length < 2) return;
    try {
      window.localStorage.setItem(liveShowsLocationKey, location);
    } catch {
      // Remembering the location is optional.
    }
    await ctx.loadLiveShows({ location });
  };

  ctx.findLiveShowsNearMe = function findLiveShowsNearMe() {
    if (!navigator.geolocation) {
      ctx.liveShowsError.value = 'Current location is not available on this system.';
      return;
    }

    ctx.liveShowsUsingCurrentLocation.value = true;
    ctx.liveShowsLoading.value = true;
    ctx.liveShowsError.value = '';
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        void ctx.loadLiveShows({ lat: coords.latitude, lng: coords.longitude });
      },
      (error) => {
        ctx.liveShowsLoading.value = false;
        ctx.liveShowsUsingCurrentLocation.value = false;
        ctx.liveShowsError.value = error.code === error.PERMISSION_DENIED
          ? 'Location permission was denied. Enter a city or ZIP code instead.'
          : 'Could not determine your current location.';
      },
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 900_000 }
    );
  };
}
