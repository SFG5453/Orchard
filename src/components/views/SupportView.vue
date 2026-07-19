<script>
import { computed, onBeforeUnmount, ref } from 'vue';

export default {
  name: 'SupportView',
  props: { app: { type: Object, required: true } },
  setup(props) {
    const composing = ref(false);
    const reportType = ref('bug');
    const title = ref('');
    const body = ref('');
    const includeDiagnostics = ref(false);
    const screenshot = ref(null);
    const screenshotPreview = ref('');
    const replyBody = ref('');
    const replyScreenshot = ref(null);
    const replyPreview = ref('');
    const fileInput = ref(null);
    const replyFileInput = ref(null);

    const diagnosticsPreview = computed(() => (
      props.app.diagnostics.value.report
        ? JSON.stringify(props.app.diagnostics.value.report, null, 2)
        : 'Diagnostics will be collected when the report is sent.'
    ));
    const reportClosed = computed(() => !['open', 'waiting_on_user'].includes(
      props.app.supportActiveReport.value?.status
    ));
    const activeAttachments = computed(() => Object.fromEntries(
      props.app.supportAttachments.value.map((attachment) => [attachment.messageId, attachment])
    ));

    function setImage(target, previewTarget, file) {
      if (!file) return;
      if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type) || file.size > 5 * 1024 * 1024) {
        props.app.supportMessage.value = 'Screenshots must be PNG, JPEG, or WebP and 5 MiB or smaller.';
        return;
      }
      if (previewTarget.value) URL.revokeObjectURL(previewTarget.value);
      target.value = file;
      previewTarget.value = URL.createObjectURL(file);
    }

    function pickReportImage(event) {
      const input = event.currentTarget;
      setImage(screenshot, screenshotPreview, input?.files?.[0]);
      if (input) input.value = '';
    }

    function pickReplyImage(event) {
      const input = event.currentTarget;
      setImage(replyScreenshot, replyPreview, input?.files?.[0]);
      if (input) input.value = '';
    }

    function imageFromTransfer(event, target, previewTarget) {
      const file = [...(event.clipboardData?.files || event.dataTransfer?.files || [])]
        .find((item) => item.type.startsWith('image/'));
      if (file) {
        event.preventDefault();
        setImage(target, previewTarget, file);
      }
    }

    async function capture(target, previewTarget) {
      try {
        setImage(target, previewTarget, await props.app.captureSupportScreenshot());
      } catch (error) {
        props.app.supportMessage.value = error.message;
      }
    }

    function clearImage(target, previewTarget) {
      if (previewTarget.value) URL.revokeObjectURL(previewTarget.value);
      target.value = null;
      previewTarget.value = '';
    }

    function openFileInput(input) {
      input?.click();
    }

    async function submitReport() {
      try {
        if (includeDiagnostics.value) await props.app.collectDiagnostics();
        const sent = await props.app.submitSupportReport({
          type: reportType.value,
          title: title.value,
          body: body.value,
          diagnostics: includeDiagnostics.value ? props.app.diagnostics.value.report : null,
          screenshot: screenshot.value
        });
        if (!sent) return;
        clearImage(screenshot, screenshotPreview);
        title.value = '';
        body.value = '';
        includeDiagnostics.value = false;
        composing.value = false;
      } catch (error) {
        props.app.supportMessage.value = error.message || 'Could not send the report.';
      }
    }

    async function submitReply() {
      if (!replyBody.value.trim()) return;
      const sent = await props.app.replyToSupportReport(replyBody.value, replyScreenshot.value);
      if (!sent) return;
      replyBody.value = '';
      clearImage(replyScreenshot, replyPreview);
    }

    async function deleteReport() {
      if (!window.confirm('Delete this report, its messages, and screenshots permanently?')) return;
      await props.app.deleteSupportReport();
    }

    function newReport() {
      props.app.closeSupportReport();
      composing.value = true;
      void props.app.loadSupportGithubAuth();
    }

    function showInbox() {
      composing.value = false;
      props.app.closeSupportReport();
    }

    onBeforeUnmount(() => {
      clearImage(screenshot, screenshotPreview);
      clearImage(replyScreenshot, replyPreview);
    });

    return {
      ...props.app,
      activeAttachments,
      body,
      capture,
      clearImage,
      composing,
      deleteReport,
      diagnosticsPreview,
      fileInput,
      imageFromTransfer,
      includeDiagnostics,
      newReport,
      openFileInput,
      pickReplyImage,
      pickReportImage,
      replyBody,
      replyFileInput,
      replyPreview,
      replyScreenshot,
      reportClosed,
      reportType,
      screenshot,
      screenshotPreview,
      showInbox,
      submitReply,
      submitReport,
      title
    };
  }
};
</script>

<template>
  <main class="support-view">
    <aside class="support-inbox" aria-label="Support reports">
      <div class="support-inbox__toolbar">
        <strong>Your reports</strong>
        <div class="support-inbox__actions">
          <a class="support-secondary-button" :href="supportIssueTrackerUrl" target="_blank" rel="noreferrer">Current issues</a>
          <button type="button" class="support-icon-button" title="Refresh reports" @click="loadSupportReports()">
            <q-icon name="refresh" />
          </button>
        </div>
      </div>
      <button type="button" class="support-new-button" @click="newReport">
        <q-icon name="add" />
        New report
      </button>
      <div class="support-report-list">
        <button
          v-for="report in supportReports"
          :key="report.id"
          type="button"
          class="support-report-row"
          :class="{ 'support-report-row--active': supportActiveReport?.id === report.id, 'support-report-row--unread': report.unread }"
          @click="composing = false; openSupportReport(report)"
        >
          <span class="support-report-row__title">{{ report.title }}</span>
          <span class="support-report-row__meta">
            <span>{{ supportStatusLabel(report.status) }}</span>
            <time>{{ new Date(report.updatedAt).toLocaleDateString() }}</time>
          </span>
        </button>
        <p v-if="!supportReports.length && !supportLoading" class="support-empty-copy">No reports yet.</p>
      </div>
    </aside>

    <section v-if="composing" class="support-workspace" aria-labelledby="support-new-title">
      <header class="support-workspace__header">
        <div>
          <h2 id="support-new-title">New report</h2>
          <p>Your description is mirrored to Orchard's public issue tracker. Replies, diagnostics, and images stay private.</p>
        </div>
        <button type="button" class="support-text-button" @click="showInbox">Cancel</button>
      </header>

      <form class="support-form" @submit.prevent="submitReport" @paste="imageFromTransfer($event, screenshot, screenshotPreview)" @drop.prevent="imageFromTransfer($event, screenshot, screenshotPreview)" @dragover.prevent>
        <label class="support-form__type">
          <span>Type</span>
          <select v-model="reportType">
            <option value="bug">Bug</option>
            <option value="feature">Feature request</option>
            <option value="artist_page">Artist page request</option>
            <option value="feedback">Feedback</option>
          </select>
        </label>
        <label>
          <span>Title</span>
          <input v-model="title" required maxlength="140" placeholder="A short summary" />
        </label>
        <label>
          <span>What happened?</span>
          <textarea v-model="body" required maxlength="12000" rows="8" placeholder="Tell us what you expected and what Orchard did instead."></textarea>
        </label>

        <div class="support-attachment-row">
          <input ref="fileInput" hidden type="file" accept="image/png,image/jpeg,image/webp" @change="pickReportImage" />
          <button type="button" class="support-secondary-button" @click="openFileInput(fileInput)"><q-icon name="attach_file" />Choose image</button>
          <button type="button" class="support-secondary-button" @click="capture(screenshot, screenshotPreview)"><q-icon name="screenshot_monitor" />Capture Orchard</button>
          <span>Paste or drop an image here, up to 5 MiB.</span>
        </div>
        <div v-if="screenshotPreview" class="support-image-preview">
          <img :src="screenshotPreview" alt="Screenshot preview" />
          <button type="button" @click="clearImage(screenshot, screenshotPreview)">Remove</button>
        </div>

        <label class="support-check-row">
          <input v-model="includeDiagnostics" type="checkbox" />
          <span>Include sanitized diagnostics</span>
        </label>
        <details v-if="includeDiagnostics" class="support-diagnostics-preview">
          <summary>Preview diagnostics</summary>
          <pre>{{ diagnosticsPreview }}</pre>
        </details>

        <section class="support-github-auth" aria-labelledby="support-github-title">
          <q-icon name="code" />
          <div>
            <strong id="support-github-title">GitHub attribution</strong>
            <p v-if="supportGithubAuth.status === 'connected'">
              The public issue will be posted as <b>@{{ supportGithubAuth.login }}</b>.
            </p>
            <p v-else-if="supportGithubAuth.status === 'pending'">
              Enter <code>{{ supportGithubAuth.userCode }}</code> in the GitHub page opened in your browser.
            </p>
            <p v-else-if="supportGithubAuth.status === 'error'">
              {{ supportGithubAuth.message }}
            </p>
            <p v-else>Connect GitHub to show your account as the author of the public issue.</p>
          </div>
          <button
            v-if="supportGithubAuth.status === 'connected'"
            type="button"
            class="support-text-button"
            @click="disconnectSupportGithub"
          >Disconnect</button>
          <button
            v-else-if="!['pending', 'loading', 'unavailable'].includes(supportGithubAuth.status)"
            type="button"
            class="support-secondary-button"
            @click="connectSupportGithub"
          >Connect GitHub</button>
          <q-spinner v-else-if="['pending', 'loading'].includes(supportGithubAuth.status)" size="20px" />
        </section>

        <div class="support-form__footer">
          <p>Cookies, authentication headers, playback URLs, diagnostics, screenshots, and music account identifiers are never posted publicly.</p>
          <button type="submit" class="support-primary-button" :disabled="supportSubmitting">
            <q-spinner v-if="supportSubmitting" size="16px" />
            <q-icon v-else name="send" />
            Send report
          </button>
        </div>
      </form>
    </section>

    <section v-else-if="supportActiveReport" class="support-workspace support-conversation" aria-labelledby="support-thread-title">
      <header class="support-workspace__header">
        <div>
          <button type="button" class="support-back-button" @click="showInbox"><q-icon name="arrow_back" />Reports</button>
          <h2 id="support-thread-title">{{ supportActiveReport.title }}</h2>
          <p>
            {{ supportStatusLabel(supportActiveReport.status) }} · {{ supportActiveReport.id.slice(0, 8) }}
            <template v-if="supportActiveReport.externalIssueUrl">
              · <a :href="supportActiveReport.externalIssueUrl" target="_blank" rel="noreferrer">Public issue</a>
            </template>
          </p>
        </div>
        <button type="button" class="support-delete-button" :disabled="supportSubmitting" @click="deleteReport">Delete</button>
      </header>

      <div class="support-message-list" aria-live="polite">
        <article v-for="message in supportMessages" :key="message.id" class="support-message" :class="`support-message--${message.sender}`">
          <header>
            <strong>{{ message.sender === 'staff' ? 'Orchard Support' : message.sender === 'system' ? 'Orchard' : 'You' }}</strong>
            <time>{{ new Date(message.createdAt).toLocaleString() }}</time>
          </header>
          <p>{{ message.body }}</p>
          <img
            v-if="activeAttachments[message.id] && supportAttachmentUrls[activeAttachments[message.id].id]"
            :src="supportAttachmentUrls[activeAttachments[message.id].id]"
            :alt="activeAttachments[message.id].filename"
            class="support-message__image"
          />
        </article>
      </div>

      <form v-if="!reportClosed" class="support-reply" @submit.prevent="submitReply" @paste="imageFromTransfer($event, replyScreenshot, replyPreview)" @drop.prevent="imageFromTransfer($event, replyScreenshot, replyPreview)" @dragover.prevent>
        <textarea v-model="replyBody" required maxlength="12000" rows="3" placeholder="Reply to Orchard Support"></textarea>
        <div v-if="replyPreview" class="support-reply__preview">
          <img :src="replyPreview" alt="Reply screenshot preview" />
          <button type="button" @click="clearImage(replyScreenshot, replyPreview)">Remove</button>
        </div>
        <div class="support-reply__actions">
          <input ref="replyFileInput" hidden type="file" accept="image/png,image/jpeg,image/webp" @change="pickReplyImage" />
          <button type="button" class="support-icon-button" title="Attach image" @click="openFileInput(replyFileInput)"><q-icon name="attach_file" /></button>
          <button type="button" class="support-icon-button" title="Capture Orchard" @click="capture(replyScreenshot, replyPreview)"><q-icon name="screenshot_monitor" /></button>
          <span></span>
          <button type="submit" class="support-primary-button" :disabled="supportSubmitting || !replyBody.trim()">Send reply</button>
        </div>
      </form>
      <p v-else class="support-closed-note">This report is closed. Your messages remain available until you delete the report or its one-year retention period ends.</p>
    </section>

    <section v-else class="support-workspace support-welcome">
      <q-icon name="support_agent" />
      <h2>Orchard Support</h2>
      <p>Report a problem, suggest something new, or continue a private conversation with the support team.</p>
      <a class="support-secondary-button" :href="supportIssueTrackerUrl" target="_blank" rel="noreferrer">Current issues</a>
      <button type="button" class="support-primary-button" @click="newReport">Create a report</button>
    </section>

    <div v-if="supportMessage" class="support-toast" role="status">{{ supportMessage }}</div>
  </main>
</template>
