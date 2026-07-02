# Transfer P2P Worker

Cloudflare Worker for signaling and TURN credential issuance.

## Setup

1. Install dependencies: `npm install`
2. Copy `wrangler.toml` and set `PUBLIC_BASE_URL`
3. Optional: set secrets for Cloudflare Realtime TURN:
   - `wrangler secret put TURN_KEY_ID`
   - `wrangler secret put TURN_KEY_API_TOKEN`
4. Deploy: `npm run deploy`

## Endpoints

- `POST /rooms` - create signaling room
- `GET /rooms/:code` - join room metadata
- `GET /rooms/:code/ws` - WebSocket signaling
- `POST /turn-credentials` - short-lived ICE servers
- `POST /devices/register` - persistent device pairing
- `POST /devices/claim` - claim pairing code
- `GET /devices/:id/trusted` - list trusted devices
