/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

const STORAGE_KEYS = {
  SUPABASE_URL: 'orchard_supabase_url',
  SUPABASE_ANON_KEY: 'orchard_supabase_anon_key',
  AUTH_SESSION: 'orchard_supabase_session'
};

const DEFAULT_URL = import.meta.env?.VITE_SUPABASE_URL || 'https://hhosnulqxwjbqqjkxuqv.supabase.co';
const DEFAULT_ANON_KEY = import.meta.env?.VITE_SUPABASE_ANON_KEY || 'sb_publishable_E9OfGgsFZDXZm-AXP2_-1g_Fe48ECNC';

class SupabaseClient {
  constructor() {
    this.listeners = new Set();
    this.session = this._loadStoredSession();
  }

  _loadStoredSession() {
    try {
      const raw = localStorage.getItem(STORAGE_KEYS.AUTH_SESSION);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }

  getUrl() {
    return localStorage.getItem(STORAGE_KEYS.SUPABASE_URL) || DEFAULT_URL;
  }

  getAnonKey() {
    return localStorage.getItem(STORAGE_KEYS.SUPABASE_ANON_KEY) || DEFAULT_ANON_KEY;
  }

  setConfig({ url, anonKey }) {
    if (url !== undefined) {
      if (url) localStorage.setItem(STORAGE_KEYS.SUPABASE_URL, url.trim().replace(/\/+$/, ''));
      else localStorage.removeItem(STORAGE_KEYS.SUPABASE_URL);
    }
    if (anonKey !== undefined) {
      if (anonKey) localStorage.setItem(STORAGE_KEYS.SUPABASE_ANON_KEY, anonKey.trim());
      else localStorage.removeItem(STORAGE_KEYS.SUPABASE_ANON_KEY);
    }
    this._notify();
  }

  isConfigured() {
    return Boolean(this.getUrl() && this.getAnonKey());
  }

  getSession() {
    return this.session;
  }

  getUser() {
    return this.session?.user || null;
  }

  isAuthenticated() {
    return Boolean(this.session?.access_token);
  }

  _getAuthHeaders() {
    const key = this.getAnonKey();
    const token = this.session?.access_token || key;
    return {
      'apikey': key,
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    };
  }

  onAuthStateChange(callback) {
    this.listeners.add(callback);
    return () => this.listeners.delete(callback);
  }

  _notify() {
    for (const listener of this.listeners) {
      try {
        listener({ session: this.session, user: this.getUser(), configured: this.isConfigured() });
      } catch (err) {
        console.error('Supabase listener error:', err);
      }
    }
  }

  _setSession(session) {
    this.session = session;
    if (session) {
      localStorage.setItem(STORAGE_KEYS.AUTH_SESSION, JSON.stringify(session));
    } else {
      localStorage.removeItem(STORAGE_KEYS.AUTH_SESSION);
    }
    this._notify();
  }

  async signUp(email, password) {
    if (!this.isConfigured()) throw new Error('Supabase is not configured with URL and Anon Key');
    const url = `${this.getUrl()}/auth/v1/signup`;
    const res = await fetch(url, {
      method: 'POST',
      headers: this._getAuthHeaders(),
      body: JSON.stringify({ email, password })
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.msg || data.error_description || data.message || 'Sign up failed');
    if (data.access_token) {
      this._setSession(data);
    }
    return data;
  }

  async signInWithPassword(email, password) {
    if (!this.isConfigured()) throw new Error('Supabase is not configured with URL and Anon Key');
    const url = `${this.getUrl()}/auth/v1/token?grant_type=password`;
    const res = await fetch(url, {
      method: 'POST',
      headers: this._getAuthHeaders(),
      body: JSON.stringify({ email, password })
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.msg || data.error_description || data.message || 'Sign in failed');
    this._setSession(data);
    return data;
  }

  async signOut() {
    if (this.session?.access_token) {
      try {
        await fetch(`${this.getUrl()}/auth/v1/logout`, {
          method: 'POST',
          headers: this._getAuthHeaders()
        });
      } catch {
        // Ignore network errors during sign out
      }
    }
    this._setSession(null);
  }

  /**
   * Fetches analysis data for a batch of video IDs.
   * @param {string[]} videoIds
   * @returns {Promise<Array<object>>}
   */
  async fetchTrackAnalysis(videoIds) {
    if (!this.isConfigured() || !Array.isArray(videoIds) || videoIds.length === 0) {
      return [];
    }
    const cleanIds = videoIds.filter(Boolean);
    if (cleanIds.length === 0) return [];

    // PostgREST `in` filter syntax: video_id=in.(id1,id2,id3)
    const encodedList = `(${cleanIds.map(id => `"${id}"`).join(',')})`;
    const url = `${this.getUrl()}/rest/v1/track_analysis?video_id=in.${encodedList}&select=*`;

    const res = await fetch(url, {
      method: 'GET',
      headers: this._getAuthHeaders()
    });

    if (!res.ok) {
      const err = await res.text();
      console.warn('Failed to fetch track analysis from Supabase:', err);
      return [];
    }

    return await res.json();
  }

  /**
   * Upserts track analysis records to Supabase.
   * @param {Array<object>} records
   * @returns {Promise<boolean>}
   */
  async upsertTrackAnalysis(records) {
    if (!this.isConfigured() || !this.isAuthenticated() || !Array.isArray(records) || records.length === 0) {
      return false;
    }

    const payload = records.map(r => ({
      video_id: r.videoId || r.video_id,
      duration: Number(r.duration) || 0.0,
      bpm: Number(r.bpm) || 0.0,
      musical_key: String(r.musical_key || r.key || ''),
      key_confidence: Number(r.key_confidence || r.keyConfidence) || 0.0,
      beat_confidence: Number(r.beat_confidence || r.beatConfidence) || 0.0,
      analysis_version: Number(r.analysis_version || r.analysisVersion) || 9,
      analysis_data: r.analysis_data || r.features || r,
      analyzed_by: this.getUser()?.id || null
    })).filter(p => p.video_id && p.bpm > 0);

    if (payload.length === 0) return false;

    const url = `${this.getUrl()}/rest/v1/track_analysis`;
    const headers = {
      ...this._getAuthHeaders(),
      'Prefer': 'resolution=merge-duplicates,return=minimal'
    };

    const res = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload)
    });

    if (!res.ok) {
      const err = await res.text();
      console.warn('Failed to upsert track analysis to Supabase:', err);
      return false;
    }

    return true;
  }
}

export const supabaseClient = new SupabaseClient();
