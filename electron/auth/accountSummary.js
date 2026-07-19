// Normalizes signed-in account data returned by the browser-backed music client.
function collectAccountRenderers(value, accounts = []) {
  if (!value || typeof value !== 'object') return accounts;

  if (value.accountItemRenderer) accounts.push(value.accountItemRenderer);
  if (value.accountItem) accounts.push(value.accountItem);
  if (value.accountName && value.accountPhoto) accounts.push(value);
  for (const child of Object.values(value)) collectAccountRenderers(child, accounts);
  return accounts;
}

function accountFromInfo(info) {
  if (Array.isArray(info)) return info.find((item) => item.is_selected) || info[0] || null;

  const accountItems = info?.contents?.contents;
  if (Array.isArray(accountItems)) {
    return accountItems.find((item) => item.is_selected) || accountItems[0] || null;
  }

  return info;
}

function accountSummaryFromItem(account, { asText, bestThumbnail }) {
  if (!account) return null;

  const name = asText(account.contents?.account_name || account.account_name || account.accountName || account.title);
  const byline = asText(
    account.contents?.account_byline ||
    account.account_byline ||
    account.accountByline ||
    account.channel_handle ||
    account.channelHandle
  );
  const photo = account.contents?.account_photo || account.account_photo || account.accountPhoto || [];
  const channelId =
    account.endpoint?.payload?.browseId ||
    account.serviceEndpoint?.browseEndpoint?.browseId ||
    account.contents?.endpoint?.payload?.browseId ||
    '';

  return {
    name: name || 'Signed in',
    byline,
    thumbnail: bestThumbnail(photo),
    channelId: String(channelId || '').startsWith('UC') ? channelId : ''
  };
}

function summaryScore(summary) {
  if (!summary) return 0;
  return Number(Boolean(summary.thumbnail)) * 4
    + Number(Boolean(summary.name && summary.name !== 'Signed in')) * 2
    + Number(Boolean(summary.byline))
    + Number(Boolean(summary.channelId));
}

function betterSummary(current, candidate) {
  return summaryScore(candidate) > summaryScore(current) ? candidate : current;
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function rawSelectedAccount(yt) {
  const response = await yt.actions.execute('/account/accounts_list', {
    client: 'TV'
  });
  const accounts = collectAccountRenderers(response?.data || response);
  return accounts.find((item) => item.isSelected) || accounts[0] || null;
}

export function createAccountSummary({ asText, bestThumbnail }) {
  return async function accountSummary(yt, includeAll = false) {
    let bestSummary = null;

    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const account = accountFromInfo(await yt.account.getInfo(includeAll));
        const summary = accountSummaryFromItem(account, { asText, bestThumbnail });
        bestSummary = betterSummary(bestSummary, summary);
        if (bestSummary?.thumbnail) return bestSummary;
      } catch {
        // The installed youtubei.js account endpoint may omit its API path.
      }

      try {
        const account = await rawSelectedAccount(yt);
        const summary = accountSummaryFromItem(account, { asText, bestThumbnail });
        bestSummary = betterSummary(bestSummary, summary);
        if (bestSummary?.thumbnail) return bestSummary;
      } catch {
        // A short retry avoids caching a temporary YouTube failure for the session.
      }

      if (attempt === 0) await wait(250);
    }

    return bestSummary || { name: 'Signed in', byline: 'YouTube Music', thumbnail: null };
  };
}
