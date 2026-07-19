import { REPORT_TYPE_LABELS } from './constants';
import { getReport, listMessages } from './database';
import type { MessageRow, ReportRow, SupportEnv } from './types';

const githubApi = 'https://api.github.com';
const githubApiVersion = '2022-11-28';
const userAttributionWindowMs = 10 * 60 * 1000;

interface GithubIssueResponse {
  number?: number;
  html_url?: string;
  url?: string;
}

export async function createGithubIssue(
  env: SupportEnv,
  reportId: string,
  options: { token?: string; force?: boolean } = {}
): Promise<boolean> {
  const report = await getReport(env, reportId);
  if (!report) return false;
  if (report.external_issue_number || report.external_issue_url) return true;
  if (
    report.github_user_pending &&
    !options.force &&
    Date.now() - Date.parse(report.created_at) < userAttributionWindowMs
  ) return false;
  const token = options.token || env.GITHUB_TOKEN;
  if (!env.GITHUB_REPOSITORY || !token) {
    await markExternalIssueDispatchFailed(env, report.id);
    return false;
  }

  const initial = (await listMessages(env, report.id))[0];
  if (!initial) return false;

  try {
    const response = await githubRequest(token, issuePath(env.GITHUB_REPOSITORY), {
      method: 'POST',
      body: JSON.stringify({
        title: issueTitle(report),
        body: issueBody(report, initial),
        labels: issueLabels(report)
      })
    });
    const issue = await response.json<GithubIssueResponse>();
    if (!issue.number) throw new Error('GitHub did not return an issue number.');
    await env.DB.prepare(`
      UPDATE reports
      SET external_issue_number = ?, external_issue_url = ?, external_issue_dispatch_status = 'sent',
        updated_at = ?, github_user_pending = 0
      WHERE id = ?
    `).bind(
      issue.number,
      issue.html_url || issue.url || issueWebUrl(env.GITHUB_REPOSITORY, issue.number),
      new Date().toISOString(),
      report.id
    ).run();
    return true;
  } catch (error) {
    await markExternalIssueDispatchFailed(env, report.id);
    console.error(JSON.stringify({
      message: 'github issue creation failed',
      reportId: report.id,
      error: error instanceof Error ? error.message : String(error)
    }));
    return false;
  }
}

export async function closeGithubIssue(env: SupportEnv, reportId: string): Promise<void> {
  const report = await getReport(env, reportId);
  if (!report?.external_issue_number) return;
  if (!env.GITHUB_REPOSITORY || !env.GITHUB_TOKEN) return;

  try {
    await githubRequest(env.GITHUB_TOKEN, issueDetailPath(env.GITHUB_REPOSITORY, report.external_issue_number), {
      method: 'PATCH',
      body: JSON.stringify({ state: 'closed' })
    });
  } catch (error) {
    console.error(JSON.stringify({
      message: 'github issue close failed',
      reportId: report.id,
      issueNumber: report.external_issue_number,
      error: error instanceof Error ? error.message : String(error)
    }));
  }
}

async function markExternalIssueDispatchFailed(env: SupportEnv, reportId: string): Promise<void> {
  await env.DB.prepare(`
    UPDATE reports SET external_issue_dispatch_status = 'failed', updated_at = ? WHERE id = ?
  `).bind(new Date().toISOString(), reportId).run();
}

async function githubRequest(token: string, path: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set('accept', 'application/vnd.github+json');
  headers.set('authorization', `Bearer ${token}`);
  headers.set('content-type', 'application/json');
  headers.set('user-agent', 'OrchardSupport/1.0');
  headers.set('x-github-api-version', githubApiVersion);
  const response = await fetch(`${githubApi}${path}`, { ...init, headers });
  if (!response.ok) {
    const detail = (await response.text()).slice(0, 500);
    throw new Error(`GitHub returned ${response.status}: ${detail}`);
  }
  return response;
}

function issuePath(repository: string): string {
  const [owner, repo, extra] = repository.split('/');
  if (!owner || !repo || extra) throw new Error('GITHUB_REPOSITORY must be formatted as owner/repo.');
  return `/repos/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues`;
}

function issueDetailPath(repository: string, number: number): string {
  return `${issuePath(repository)}/${encodeURIComponent(String(number))}`;
}

function issueWebUrl(repository: string, number: number): string {
  const [owner, repo] = repository.split('/');
  return `https://github.com/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/issues/${number}`;
}

function issueTitle(report: ReportRow): string {
  const label = REPORT_TYPE_LABELS[report.type];
  return `[${label}] ${report.title}`.slice(0, 255);
}

function issueLabels(report: ReportRow): string[] {
  return {
    bug: ['bug'],
    feature: ['enhancement'],
    feedback: ['question'],
    artist_page: ['enhancement']
  }[report.type];
}

function issueBody(report: ReportRow, initial: MessageRow): string {
  const metadata = [
    `Type: ${report.type}`,
    `Status: ${report.status}`,
    `Created: ${report.created_at}`
  ];
  return `## User report\n\n${initial.body}\n\n## Metadata\n\n${metadata.map((item) => `- ${item}`).join('\n')}`;
}
