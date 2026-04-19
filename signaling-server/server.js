// Minimal 1:1 signaling relay. No auth, no rooms.
// Roles: ?role=phone | ?role=viewer . We only keep one of each.

const { WebSocketServer } = require('ws');
const url = require('url');

const PORT = process.env.PORT || 8080;
const wss = new WebSocketServer({ port: PORT });

const peers = { phone: null, viewer: null };
const other = (role) => (role === 'phone' ? 'viewer' : 'phone');

wss.on('connection', (ws, req) => {
  const role = url.parse(req.url, true).query.role;
  if (role !== 'phone' && role !== 'viewer') {
    ws.close();
    return;
  }

  if (peers[role]) {
    try { peers[role].close(); } catch (_) {}
  }
  peers[role] = ws;
  console.log(`[+] ${role} connected`);

  // When viewer joins and phone is already there, tell the phone to make an offer.
  if (role === 'viewer' && peers.phone && peers.phone.readyState === 1) {
    peers.phone.send(JSON.stringify({ type: 'viewer-ready' }));
  }

  ws.on('message', (data) => {
    const target = peers[other(role)];
    if (target && target.readyState === 1) {
      target.send(data.toString());
    }
  });

  ws.on('close', () => {
    if (peers[role] === ws) peers[role] = null;
    console.log(`[-] ${role} disconnected`);
  });
});

console.log(`Signaling server listening on :${PORT}`);
