import { computed, ref } from 'vue';

const SUPPORT_ENDPOINT = 'https://support.sfg545.dev';
const SUPPORT_ISSUES_URL = 'https://github.com/SFG5453/Orchard/issues';
const SUPPORT_IDENTITY_KEY = 'orchard:support-identity';
const MAX_SCREENSHOT_BYTES = 5 * 1024 * 1024;
const screenshotTypes = new Set(['image/png', 'image/jpeg', 'image/webp']);

function readIdentity() {
  try {
    const value = JSON.parse(window.localStorage.getItem(SUPPORT_IDENTITY_KEY) || 'null');
    if (value?.clientId && value?.secret) return value;
  } catch {
    // A corrupt support identity is replaced on the next request.
  }
  return null;
}

function writeIdentity(identity) {
  window.localStorage.setItem(SUPPORT_IDENTITY_KEY, JSON.stringify(identity));
}

function attachmentUrl(path) {
  return new URL(path, SUPPORT_ENDPOINT).toString();
}

function imageFile(file) {
  if (!(file instanceof File)) throw new Error('Choose an image to attach.');
  if (!screenshotTypes.has(file.type)) throw new Error('Screenshots must be PNG, JPEG, or WebP.');
  if (!file.size || file.size > MAX_SCREENSHOT_BYTES) throw new Error('Screenshots must be 5 MiB or smaller.');
  return file;
}

export function installSupportActions(ctx) {
  ctx.supportIdentity = ref(readIdentity());
  ctx.supportReports = ref([]);
  ctx.supportActiveReport = ref(null);
  ctx.supportMessages = ref([]);
  ctx.supportAttachments = ref([]);
  ctx.supportAttachmentUrls = ref({});
  ctx.supportLoading = ref(false);
  ctx.supportSubmitting = ref(false);
  ctx.supportMessage = ref('');
  ctx.supportGithubAuth = ref({ status: 'loading' });
  ctx.supportGithubTimer = 0;
  ctx.supportPollTimer = 0;
  ctx.supportIssueTrackerUrl = SUPPORT_ISSUES_URL;
  ctx.supportUnreadCount = computed(() => ctx.supportReports.value.filter((report) => report.unread).length);

  ctx.supportStatusLabel = function supportStatusLabel(status = '') {
    return {
      open: 'Open',
      waiting_on_user: 'Waiting on you',
      fixed: 'Fixed',
      resolved: 'Resolved',
      duplicate: 'Duplicate',
      unable_to_reproduce: 'Unable to reproduce',
      declined: 'Declined',
      closed: 'Closed'
    }[status] || 'Open';
  };

  ctx.loadSupportGithubAuth = async function loadSupportGithubAuth() {
    window.clearTimeout(ctx.supportGithubTimer);
    if (!window.orchardGithub) {
      ctx.supportGithubAuth.value = { status: 'unavailable' };
      return ctx.supportGithubAuth.value;
    }
    try {
      ctx.supportGithubAuth.value = await window.orchardGithub.status();
    } catch (error) {
      ctx.supportGithubAuth.value = { status: 'error', message: error.message };
    }
    if (ctx.supportGithubAuth.value.status === 'pending') {
      ctx.supportGithubTimer = window.setTimeout(() => void ctx.loadSupportGithubAuth(), 2000);
    }
    return ctx.supportGithubAuth.value;
  };

  ctx.connectSupportGithub = async function connectSupportGithub() {
    try {
      ctx.supportGithubAuth.value = await window.orchardGithub.connect();
      ctx.supportMessage.value = 'Finish GitHub sign-in in your browser, then return to Orchard.';
      ctx.supportGithubTimer = window.setTimeout(() => void ctx.loadSupportGithubAuth(), 2000);
    } catch (error) {
      ctx.supportGithubAuth.value = { status: 'error', message: error.message };
      ctx.supportMessage.value = error.message;
    }
  };

  ctx.disconnectSupportGithub = async function disconnectSupportGithub() {
    ctx.supportGithubAuth.value = await window.orchardGithub.disconnect();
    ctx.supportMessage.value = 'GitHub disconnected from Orchard Support.';
  };

  ctx.ensureSupportIdentity = async function ensureSupportIdentity() {
    if (ctx.supportIdentity.value?.clientId && ctx.supportIdentity.value?.secret) return ctx.supportIdentity.value;
    const response = await fetch(`${SUPPORT_ENDPOINT}/v1/clients`, { method: 'POST' });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(payload.error || 'Could not create a private support identity.');
    const identity = { clientId: payload.clientId, secret: payload.secret };
    writeIdentity(identity);
    ctx.supportIdentity.value = identity;
    return identity;
  };

  ctx.supportFetch = async function supportFetch(path, options = {}) {
    const identity = await ctx.ensureSupportIdentity();
    const { raw = false, ...fetchOptions } = options;
    const headers = new Headers(options.headers || {});
    headers.set('authorization', `Bearer ${identity.clientId}.${identity.secret}`);
    const response = await fetch(`${SUPPORT_ENDPOINT}${path}`, { ...fetchOptions, headers });
    const contentType = response.headers.get('content-type') || '';
    if (raw) {
      if (!response.ok) throw new Error('Could not load the support attachment.');
      return response;
    }
    const payload = contentType.includes('application/json') ? await response.json().catch(() => ({})) : {};
    if (!response.ok) throw new Error(payload.error || 'The support service is unavailable.');
    return payload;
  };

  ctx.loadSupportReports = async function loadSupportReports({ quiet = false } = {}) {
    if (quiet && !ctx.supportIdentity.value) return [];
    if (!quiet) ctx.supportLoading.value = true;
    try {
      const payload = await ctx.supportFetch('/v1/reports');
      ctx.supportReports.value = payload.reports || [];
      if (ctx.supportActiveReport.value) {
        const updated = ctx.supportReports.value.find((report) => report.id === ctx.supportActiveReport.value.id);
        if (updated) ctx.supportActiveReport.value = { ...ctx.supportActiveReport.value, ...updated };
      }
      ctx.supportMessage.value = '';
      return ctx.supportReports.value;
    } catch (error) {
      if (!quiet) ctx.supportMessage.value = error.message;
      return [];
    } finally {
      if (!quiet) ctx.supportLoading.value = false;
    }
  };

  ctx.openSupportReport = async function openSupportReport(report, { quiet = false } = {}) {
    if (!quiet) ctx.supportLoading.value = true;
    ctx.clearSupportAttachmentUrls();
    try {
      const payload = await ctx.supportFetch(`/v1/reports/${encodeURIComponent(report.id)}`);
      ctx.supportActiveReport.value = { ...report, ...payload.report, unread: false };
      ctx.supportMessages.value = payload.messages || [];
      ctx.supportAttachments.value = payload.attachments || [];
      await ctx.supportFetch(`/v1/reports/${encodeURIComponent(report.id)}/read`, { method: 'POST' });
      ctx.supportReports.value = ctx.supportReports.value.map((item) => (
        item.id === report.id ? { ...item, unread: false } : item
      ));
      await ctx.loadSupportAttachmentUrls();
    } catch (error) {
      ctx.supportMessage.value = error.message;
    } finally {
      if (!quiet) ctx.supportLoading.value = false;
    }
  };

  ctx.closeSupportReport = function closeSupportReport() {
    ctx.clearSupportAttachmentUrls();
    ctx.supportActiveReport.value = null;
    ctx.supportMessages.value = [];
    ctx.supportAttachments.value = [];
  };

  ctx.submitSupportReport = async function submitSupportReport(input) {
    ctx.supportSubmitting.value = true;
    try {
      const form = new FormData();
      form.set('type', input.type);
      form.set('title', input.title);
      form.set('body', input.body);
      if (input.diagnostics) form.set('diagnostics', JSON.stringify(input.diagnostics));
      if (input.screenshot) form.set('screenshot', imageFile(input.screenshot));
      const githubLogin = ctx.supportGithubAuth.value.status === 'connected'
        ? ctx.supportGithubAuth.value.login
        : '';
      if (githubLogin) form.set('githubUserPending', 'true');
      const payload = await ctx.supportFetch('/v1/reports', { method: 'POST', body: form });
      let attributionError = '';
      if (githubLogin) {
        const identity = await ctx.ensureSupportIdentity();
        try {
          await window.orchardGithub.createIssue({
            reportId: payload.report.id,
            authorization: `Bearer ${identity.clientId}.${identity.secret}`
          });
        } catch (error) {
          attributionError = error.message || 'GitHub attribution failed.';
        }
      }
      await ctx.loadSupportReports({ quiet: true });
      await ctx.openSupportReport(payload.report);
      ctx.supportMessage.value = attributionError
        ? `${attributionError} Orchard will retry the public issue without attribution.`
        : githubLogin
          ? `Report sent and posted publicly as @${githubLogin}.`
          : 'Report sent to Orchard Support and queued for the public issue tracker.';
      return true;
    } catch (error) {
      ctx.supportMessage.value = error.message;
      return false;
    } finally {
      ctx.supportSubmitting.value = false;
    }
  };

  ctx.replyToSupportReport = async function replyToSupportReport(body, screenshot = null) {
    if (!ctx.supportActiveReport.value) return false;
    ctx.supportSubmitting.value = true;
    try {
      const form = new FormData();
      form.set('body', body);
      if (screenshot) form.set('screenshot', imageFile(screenshot));
      await ctx.supportFetch(`/v1/reports/${encodeURIComponent(ctx.supportActiveReport.value.id)}/messages`, {
        method: 'POST',
        body: form
      });
      await ctx.openSupportReport(ctx.supportActiveReport.value);
      return true;
    } catch (error) {
      ctx.supportMessage.value = error.message;
      return false;
    } finally {
      ctx.supportSubmitting.value = false;
    }
  };

  ctx.deleteSupportReport = async function deleteSupportReport() {
    if (!ctx.supportActiveReport.value) return false;
    ctx.supportSubmitting.value = true;
    try {
      await ctx.supportFetch(`/v1/reports/${encodeURIComponent(ctx.supportActiveReport.value.id)}`, { method: 'DELETE' });
      ctx.closeSupportReport();
      await ctx.loadSupportReports({ quiet: true });
      ctx.supportMessage.value = 'Report and conversation deleted.';
      return true;
    } catch (error) {
      ctx.supportMessage.value = error.message;
      return false;
    } finally {
      ctx.supportSubmitting.value = false;
    }
  };

  ctx.captureSupportScreenshot = async function captureSupportScreenshot() {
    const dataUrl = await window.orchardApp?.captureScreenshot?.();
    if (!dataUrl) throw new Error('Could not capture the Orchard window.');
    const blob = await fetch(dataUrl).then((response) => response.blob());
    return imageFile(new File([blob], `orchard-${Date.now()}.png`, { type: 'image/png' }));
  };

  ctx.loadSupportAttachmentUrls = async function loadSupportAttachmentUrls() {
    const urls = {};
    await Promise.all(ctx.supportAttachments.value.map(async (attachment) => {
      const response = await ctx.supportFetch(attachmentUrl(attachment.url).replace(SUPPORT_ENDPOINT, ''), { raw: true });
      urls[attachment.id] = URL.createObjectURL(await response.blob());
    }));
    ctx.supportAttachmentUrls.value = urls;
  };

  ctx.clearSupportAttachmentUrls = function clearSupportAttachmentUrls() {
    Object.values(ctx.supportAttachmentUrls.value).forEach((url) => URL.revokeObjectURL(url));
    ctx.supportAttachmentUrls.value = {};
  };

  ctx.scheduleSupportPolling = function scheduleSupportPolling() {
    window.clearTimeout(ctx.supportPollTimer);
    const delay = ctx.activeView.value === 'support' ? 15_000 : 300_000;
    ctx.supportPollTimer = window.setTimeout(async () => {
      const activeId = ctx.supportActiveReport.value?.id;
      await ctx.loadSupportReports({ quiet: true });
      if (ctx.activeView.value === 'support' && activeId) {
        const activeReport = ctx.supportReports.value.find((report) => report.id === activeId) || ctx.supportActiveReport.value;
        await ctx.openSupportReport(activeReport, { quiet: true });
      }
      ctx.scheduleSupportPolling();
    }, delay);
  };

  ctx.refreshSupportOnFocus = function refreshSupportOnFocus() {
    void ctx.loadSupportReports({ quiet: true });
    ctx.scheduleSupportPolling();
  };

  ctx.stopSupportPolling = function stopSupportPolling() {
    window.clearTimeout(ctx.supportPollTimer);
    window.clearTimeout(ctx.supportGithubTimer);
    ctx.clearSupportAttachmentUrls();
  };
}
