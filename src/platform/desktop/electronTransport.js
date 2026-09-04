import { io } from 'socket.io-client';

export function createElectronTransport(port) {
  return io(`http://127.0.0.1:${port}`, { transports: ['websocket'] });
}
