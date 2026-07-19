export type SupportEnv = Env & {
  DISCORD_APPLICATION_ID: string;
  DISCORD_BOT_TOKEN: string;
  DISCORD_FORUM_CHANNEL_ID: string;
  DISCORD_GUILD_ID: string;
  DISCORD_PUBLIC_KEY: string;
  DISCORD_STAFF_ROLE_IDS: string;
  GITHUB_CLIENT_ID: string;
  GITHUB_REPOSITORY: string;
  GITHUB_TOKEN: string;
};

export type ReportType = 'bug' | 'feature' | 'feedback' | 'artist_page';
export type ReportStatus =
  | 'open'
  | 'waiting_on_user'
  | 'fixed'
  | 'resolved'
  | 'duplicate'
  | 'unable_to_reproduce'
  | 'declined'
  | 'closed';
export type MessageSender = 'user' | 'staff' | 'system';

export interface ClientRow {
  id: string;
  secret_hash: string;
  created_at: string;
  last_seen_at: string;
}

export interface ReportRow {
  id: string;
  client_id: string;
  type: ReportType;
  title: string;
  status: ReportStatus;
  target_version: string;
  diagnostics_json: string | null;
  discord_thread_id: string | null;
  dispatch_status: 'pending' | 'sent' | 'failed';
  external_issue_number: number | null;
  external_issue_url: string | null;
  external_issue_dispatch_status: 'pending' | 'sent' | 'failed';
  github_user_pending: number;
  user_read_at: string | null;
  created_at: string;
  updated_at: string;
  closed_at: string | null;
}

export interface MessageRow {
  id: string;
  report_id: string;
  sender: MessageSender;
  body: string;
  discord_message_id: string | null;
  created_at: string;
}

export interface AttachmentRow {
  id: string;
  report_id: string;
  message_id: string;
  r2_key: string;
  filename: string;
  content_type: string;
  byte_size: number;
  created_at: string;
}

export interface AuthenticatedClient {
  client: ClientRow;
  token: string;
}

export interface DiscordInteraction {
  type: number;
  guild_id?: string;
  channel_id?: string;
  member?: { roles?: string[]; nick?: string; user?: { id?: string; username?: string; global_name?: string } };
  data?: {
    name?: string;
    options?: Array<{ name: string; value?: string }>;
  };
}
