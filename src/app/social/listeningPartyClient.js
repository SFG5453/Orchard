const DEFAULT_PARTY_URL = 'https://party.sfg545.dev';
const CONNECTION_TIMEOUT_MS = 10_000;

export class ListeningPartyClient extends EventTarget {
  constructor({ serviceUrl = DEFAULT_PARTY_URL, name = 'Listener' } = {}) {
    super();
    this.serviceUrl = serviceUrl.replace(/\/+$/, '');
    this.name = name;
    this.socket = null;
    this.room = null;
    this.participant = null;
    this.peers = new Map();
    this.iceServers = [];
    this.status = 'idle';
  }

  async createRoom(options = {}) {
    const data = await this.request('/rooms', {
      method: 'POST',
      body: { hostName: options.name || this.name }
    });
    await this.connect(data);
    return data;
  }

  async joinRoom(roomId, options = {}) {
    const data = await this.request(`/rooms/${cleanRoomId(roomId)}/join`, {
      method: 'POST',
      body: { name: options.name || this.name }
    });
    await this.connect(data);
    return data;
  }

  async request(path, options = {}) {
    const response = await fetch(`${this.serviceUrl}${path}`, {
      method: options.method || 'GET',
      headers: { 'content-type': 'application/json' },
      body: options.body ? JSON.stringify(options.body) : undefined
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok || !payload.ok) throw new Error(payload.error || `Party request failed (${response.status}).`);
    return payload.data;
  }

  async connect({ room, participant }) {
    this.disconnect();
    this.room = room;
    this.participant = participant;
    this.setStatus('connecting');

    const socketUrl = new URL(room.socketUrl || `${this.serviceUrl.replace(/^http/, 'ws')}/rooms/${room.id}/socket`);
    socketUrl.searchParams.set('participantId', participant.id);
    socketUrl.searchParams.set('token', participant.token);
    const socket = new WebSocket(socketUrl);
    this.socket = socket;
    socket.addEventListener('message', (event) => this.handleSocketMessage(event.data));
    socket.addEventListener('close', () => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.disconnectPeers('offline');
    });

    await new Promise((resolve, reject) => {
      let settled = false;
      const finish = (error) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        if (error) reject(error);
        else resolve();
      };
      const timeout = setTimeout(() => {
        const error = new Error('Listening party connection timed out.');
        this.emit('error', { error: error.message });
        if (this.socket === socket) socket.close();
        finish(error);
      }, CONNECTION_TIMEOUT_MS);

      socket.addEventListener('open', () => {
        if (this.socket !== socket) return finish(new Error('Listening party connection was cancelled.'));
        this.setStatus('connected');
        finish();
      }, { once: true });
      socket.addEventListener('error', () => {
        const error = new Error('Listening party connection failed.');
        this.emit('error', { error: error.message });
        finish(error);
      }, { once: true });
      socket.addEventListener('close', () => {
        finish(new Error('Listening party connection closed before it was ready.'));
      }, { once: true });
    });
  }

  disconnect() {
    if (this.socket) {
      this.socket.close(1000, 'client_disconnect');
      this.socket = null;
    }
    this.disconnectPeers('closed');
    this.room = null;
    this.participant = null;
    this.setStatus('idle');
  }

  disconnectPeers(reason) {
    for (const peer of this.peers.values()) peer.close();
    this.peers.clear();
    this.emit('peers', { peers: [], reason });
    if (reason === 'offline') this.setStatus('offline');
  }

  handleSocketMessage(raw) {
    const message = parseJson(raw);
    if (!message?.type) return;

    if (message.type === 'party:welcome') {
      this.room = message.room;
      this.iceServers = message.iceServers || [];
      this.emit('welcome', message);
      for (const peer of message.peers || []) this.ensurePeer(peer.id, this.shouldInitiate(peer.id), peer);
      this.emitPeers();
      return;
    }

    if (message.type === 'peer:joined') {
      this.ensurePeer(message.peer.id, this.shouldInitiate(message.peer.id), message.peer);
      this.emitPeers();
      return;
    }

    if (message.type === 'peer:left') {
      this.closePeer(message.participantId);
      this.emitPeers();
      return;
    }

    if (message.type === 'signal') {
      void this.handleSignal(message);
      return;
    }

    if (message.type === 'party:host-changed') {
      if (this.participant?.id === message.hostId) {
        this.participant = { ...this.participant, role: 'host' };
      } else if (this.participant?.role === 'host') {
        this.participant = { ...this.participant, role: 'guest' };
      }
      for (const peer of this.peers.values()) {
        peer.role = peer.id === message.hostId ? 'host' : peer.role === 'host' ? 'guest' : peer.role;
      }
      this.emitPeers();
      this.emit(message.type, message);
      return;
    }

    this.emit(message.type, message);
  }

  ensurePeer(peerId, initiate = false, info = {}) {
    if (!peerId || peerId === this.participant?.id) return null;
    const existing = this.peers.get(peerId);
    if (existing) {
      this.updatePeerInfo(existing, info);
      return existing;
    }

    const connection = new RTCPeerConnection({ iceServers: this.iceServers });
    const peer = {
      id: peerId,
      name: info.name || '',
      role: info.role || 'guest',
      connection,
      channel: null,
      open: false,
      pendingIce: [],
      close: () => {
        try {
          peer.channel?.close();
        } catch {
          // Already closed channels can throw in some WebRTC implementations.
        }
        try {
          peer.connection.close();
        } catch {
          // Already closed peer connections can throw in some WebRTC implementations.
        }
      }
    };
    this.peers.set(peerId, peer);

    connection.addEventListener('icecandidate', (event) => {
      if (event.candidate) this.sendSignal(peerId, 'ice', event.candidate);
    });
    connection.addEventListener('datachannel', (event) => this.attachChannel(peer, event.channel));
    connection.addEventListener('connectionstatechange', () => {
      if (['closed', 'failed', 'disconnected'].includes(connection.connectionState)) this.closePeer(peerId);
      this.emitPeers();
    });

    if (initiate) {
      this.attachChannel(peer, connection.createDataChannel('orchard-party'));
      void this.createOffer(peer);
    }

    return peer;
  }

  updatePeerInfo(peer, info = {}) {
    if (info.name) peer.name = info.name;
    if (info.role) peer.role = info.role;
  }

  async createOffer(peer) {
    const offer = await peer.connection.createOffer();
    await peer.connection.setLocalDescription(offer);
    this.sendSignal(peer.id, 'offer', peer.connection.localDescription);
  }

  async handleSignal(message) {
    const peer = this.ensurePeer(message.from, false);
    if (!peer) return;

    if (message.kind === 'offer') {
      await peer.connection.setRemoteDescription(message.data);
      await this.flushPendingIce(peer);
      const answer = await peer.connection.createAnswer();
      await peer.connection.setLocalDescription(answer);
      this.sendSignal(peer.id, 'answer', peer.connection.localDescription);
      return;
    }

    if (message.kind === 'answer') {
      await peer.connection.setRemoteDescription(message.data);
      await this.flushPendingIce(peer);
      return;
    }

    if (message.kind === 'ice' && message.data) {
      if (!peer.connection.remoteDescription) peer.pendingIce.push(message.data);
      else await peer.connection.addIceCandidate(message.data);
    }
  }

  async flushPendingIce(peer) {
    while (peer.pendingIce.length) {
      await peer.connection.addIceCandidate(peer.pendingIce.shift());
    }
  }

  attachChannel(peer, channel) {
    peer.channel = channel;
    channel.addEventListener('open', () => {
      peer.open = true;
      this.emitPeers();
    });
    channel.addEventListener('close', () => {
      peer.open = false;
      this.emitPeers();
    });
    channel.addEventListener('message', (event) => {
      const message = parseJson(event.data);
      if (message?.type) this.emit(message.type, { ...message, from: peer.id });
    });
  }

  sendSignal(to, kind, data) {
    this.sendSocket({ type: 'signal', to, kind, data });
  }

  broadcast(type, payload = {}) {
    if (this.participant?.role === 'host' && type === 'party:state') {
      this.sendSocket({ type: 'party:update', payload });
      return;
    }

    const message = JSON.stringify({ type, payload, sentAt: Date.now() });
    for (const peer of this.peers.values()) {
      if (peer.channel?.readyState === 'open') peer.channel.send(message);
    }
  }

  requestHost(payload = {}) {
    this.sendSocket({ type: 'party:request', payload });
  }

  transferHost(participantId) {
    this.sendSocket({ type: 'party:host-transfer', participantId });
  }

  closeRoom() {
    this.sendSocket({ type: 'party:close' });
  }

  sendSocket(payload) {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(payload));
  }

  shouldInitiate(peerId) {
    return String(this.participant?.id || '') < String(peerId || '');
  }

  closePeer(peerId) {
    const peer = this.peers.get(peerId);
    if (!peer) return;
    peer.close();
    this.peers.delete(peerId);
  }

  emitPeers() {
    this.emit('peers', { peers: Array.from(this.peers.values()).map((peer) => ({
      id: peer.id,
      name: peer.name,
      role: peer.role,
      open: peer.open,
      state: peer.connection.connectionState
    })) });
  }

  setStatus(status) {
    this.status = status;
    this.emit('status', { status });
  }

  emit(type, detail) {
    this.dispatchEvent(new CustomEvent(type, { detail }));
  }
}

function parseJson(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function cleanRoomId(value) {
  return String(value || '').trim().toUpperCase().replace(/[^A-Z2-9]/g, '');
}
